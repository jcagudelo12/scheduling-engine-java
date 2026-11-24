package sched.server;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.regex.Pattern;
import org.bouncycastle.math.ec.rfc8032.Ed25519;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import sched.adapters.http.AlternativesController;
import sched.adapters.http.AppSecurityHeadersFilter;
import sched.adapters.http.AppWebConfig;
import sched.adapters.http.BodySizeLimitFilter;
import sched.adapters.http.CombinationsController;
import sched.adapters.http.HealthController;
import sched.adapters.http.HttpExceptionHandler;
import sched.adapters.http.JwtAuthFilter;
import sched.adapters.http.RequestIdFilter;
import sched.adapters.http.RequestLimits;
import sched.adapters.http.auth.JwtVerifier;
import sched.application.ApplyLogEntry;
import sched.application.CatalogLogEntry;
import sched.application.CatalogReplica;
import sched.application.GenerateCombinations;
import sched.application.Readiness;
import sched.application.SuggestAlternatives;
import sched.domain.Course;
import sched.domain.CourseId;
import sched.domain.EnrollmentRules;
import sched.domain.Section;
import sched.domain.SectionId;
import sched.domain.SectionSeats;
import sched.domain.TimeSlot;
import sched.domain.Weekday;
import sched.solver.ForwardCheckingSolver;
import sched.solver.Solver;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Exportador de casos de equivalencia para la versión en Rust del motor: arranca el motor real
 * (Spring Boot con Tomcat, los controladores, los filtros y la configuración de Jackson de
 * producción, sin NATS ni gRPC), le envía solicitudes HTTP de verdad y escribe en JSON cada
 * solicitud con la respuesta exacta (código, cabeceras y cuerpo) que produjo.
 *
 * <p>Es lo más fiel a la respuesta HTTP real: pasa por Tomcat (404, 405, 415, {@code Content-Length}
 * frente a transferencia por trozos) y por los filtros en su orden de producción. Se usan los casos de
 * uso reales y el solver ForwardChecking con el tiempo máximo de búsqueda desactivado, para que el
 * resultado sea determinista; el único reloj es el del verificador del token, fijo.
 *
 * <p>Siempre escribe {@code target/request-cases.json}. Para llevar el fixture al repositorio de
 * Rust:
 *
 * <pre>
 * ./mvnw -q -pl server -am verify -DskipITs -Dsched.export.request-cases=/ruta/request-cases.json -Dtest=ExportRequestCasesTest -Dsurefire.failIfNoSpecifiedTests=false
 * </pre>
 *
 * <p>Quedan fuera a propósito los casos que solo dependen de Tomcat y no del motor (cabeceras de más
 * de 8 KiB, que Tomcat rechaza con una página HTML 400 antes de los filtros; ruta con barra invertida) y los
 * que Spring responde con un 500 (tipo de contenido {@code application/*}, {@code GET /error}).
 *
 * <p>La salida es determinista (semillas y reloj fijos): dos corridas dan el mismo archivo salvo los
 * identificadores generados al azar ({@code X-Request-Id}, normalizado a {@code <uuid>}) la fecha de los errores de
 * Spring ({@code timestamp}, normalizada a {@code <ts>}) y el host de {@code Location} ({@code <host>}).
 * Llaves de prueba: la semilla de abajo es PÚBLICA y solo sirve para estas pruebas.
 *
 * <p>Solo corre si se pasa {@code -Dsched.export.request-cases}: es una herramienta de exportación, no una
 * prueba del build, y necesita {@code bench/scenario.json}, {@code bench/students.json} y
 * {@code bench/alternatives.json}, que se generan con {@code bench/generate_scenario.py} y no se versionan.
 */
@EnabledIfSystemProperty(named = "sched.export.request-cases", matches = ".+")
class ExportRequestCasesTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Instant NOW = Instant.parse("2026-11-22T12:00:00Z");
    private static final String ISSUER = "institucion-test";
    private static final String AUDIENCE = "sched-engine";
    private static final String KID = "k1";
    /** Semilla de la llave de prueba: pública a propósito, NO es un secreto. */
    private static final byte[] TEST_SEED = HexFormat.of()
            .parseHex("4242424242424242424242424242424242424242424242424242424242424242");
    private static final Pattern UUID = Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");

    // ---- Montaje del motor (sin NATS ni gRPC) -------------------------------------------------

    /** Lo que {@code EngineConfig} compone en producción para HTTP, con otra fuente del catálogo. */
    @Configuration
    @EnableAutoConfiguration
    static class HttpOnlyConfig {

        static volatile CatalogLogEntry.Snapshot snapshot;
        static volatile JwtVerifier verifier;

        @Bean
        CatalogReplica replica() throws Exception {
            CatalogReplica replica = new CatalogReplica();
            if (snapshot != null) {
                new ApplyLogEntry(replica).execute(snapshot);
            }
            return replica;
        }

        @Bean
        Readiness readiness(CatalogReplica replica) {
            Readiness readiness = new Readiness(replica);
            readiness.markCaughtUp();
            return readiness;
        }

        @Bean
        RequestLimits requestLimits(
                @Value("${sched.limits.max-courses}") int maxCourses,
                @Value("${sched.limits.max-avoid}") int maxAvoid,
                @Value("${sched.limits.max-id-length}") int maxIdLength,
                @Value("${sched.limits.max-body-bytes}") long maxBodyBytes) {
            return new RequestLimits(maxCourses, maxAvoid, maxIdLength, maxBodyBytes);
        }

        @Bean
        RequestIdFilter requestIdFilter() {
            return new RequestIdFilter();
        }

        @Bean
        AppSecurityHeadersFilter appSecurityHeadersFilter() {
            return new AppSecurityHeadersFilter();
        }

        @Bean
        AppWebConfig appWebConfig() {
            return new AppWebConfig();
        }

        @Bean
        HealthController healthController(Readiness readiness) {
            return new HealthController(readiness);
        }

        @Bean
        BodySizeLimitFilter bodySizeLimitFilter(RequestLimits limits) {
            return new BodySizeLimitFilter(limits.maxBodyBytes());
        }

        @Bean
        HttpExceptionHandler httpExceptionHandler() {
            return new HttpExceptionHandler();
        }

        @Bean
        @org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name = "sched.auth", havingValue = "jwt")
        JwtAuthFilter jwtAuthFilter(RequestLimits limits) {
            return new JwtAuthFilter(verifier, limits);
        }

        @Bean
        CombinationsController combinationsController(
                CatalogReplica replica, @Value("${sched.max-combinations}") int max, RequestLimits limits) {
            Solver solver = new ForwardCheckingSolver();
            return new CombinationsController(new GenerateCombinations(solver, replica, max, null), limits);
        }

        @Bean
        AlternativesController alternativesController(
                CatalogReplica replica, @Value("${sched.max-alternatives}") int max, RequestLimits limits) {
            Solver solver = new ForwardCheckingSolver();
            return new AlternativesController(new SuggestAlternatives(solver, replica, max, null), limits);
        }
    }

    // ---- Catálogos --------------------------------------------------------------------------

    private record Catalog(String name, CatalogLogEntry.Snapshot snapshot, JsonNode spec) {
    }

    private static Section section(String id, String course, Weekday day, int start, int end) {
        return new Section(new SectionId(id), new CourseId(course),
                List.of(TimeSlot.of(day, start, end).orElseThrow()), 30);
    }

    /** Catálogo pequeño hecho a mano, con cupos 0, cruces, un curso sin créditos y un curso sin grupos con cupo. */
    private static Catalog mini(String name, int maxCredits) {
        List<SectionSeats> sections = List.of(
                new SectionSeats(section("MAT-01", "MAT", Weekday.MONDAY, 420, 540), 0),
                new SectionSeats(section("MAT-02", "MAT", Weekday.WEDNESDAY, 420, 540), 5),
                new SectionSeats(section("MAT-03", "MAT", Weekday.MONDAY, 450, 570), 3),
                new SectionSeats(section("FIS-01", "FIS", Weekday.TUESDAY, 420, 540), 5),
                new SectionSeats(section("FIS-02", "FIS", Weekday.MONDAY, 480, 600), 2),
                new SectionSeats(section("QUI-01", "QUI", Weekday.TUESDAY, 480, 600), 1),
                new SectionSeats(section("QUI-02", "QUI", Weekday.FRIDAY, 420, 540), 0),
                new SectionSeats(section("HIS-01", "HIS", Weekday.THURSDAY, 420, 540), 5),
                new SectionSeats(section("ART-01", "ART", Weekday.SATURDAY, 420, 540), 0));
        List<Course> courses = List.of(
                new Course(new CourseId("MAT"), 4), new Course(new CourseId("FIS"), 3),
                new Course(new CourseId("QUI"), 2), new Course(new CourseId("ART"), 1));
        CatalogLogEntry.Snapshot snapshot = new CatalogLogEntry.Snapshot(
                7, sections, courses, new EnrollmentRules(maxCredits));
        ObjectNode spec = JSON.createObjectNode();
        spec.put("seq", 7);
        spec.put("max_credits", maxCredits);
        ArrayNode cs = spec.putArray("courses");
        for (Course c : courses) {
            cs.addObject().put("id", c.id().value()).put("credits", c.credits());
        }
        ArrayNode ss = spec.putArray("sections");
        for (SectionSeats s : sections) {
            ObjectNode node = ss.addObject();
            node.put("id", s.section().id().value());
            node.put("course", s.section().course().value());
            node.put("capacity", s.section().capacity());
            node.put("available", s.available());
            ArrayNode slots = node.putArray("slots");
            for (TimeSlot slot : s.section().slots()) {
                slots.addObject().put("day", slot.day().name().toLowerCase())
                        .put("start_minute", slot.start()).put("end_minute", slot.end());
            }
        }
        return new Catalog(name, snapshot, spec);
    }

    /** {@code bench/scenario.json}: el mismo catálogo que carga el cargador de la prueba de carga. */
    private static Catalog scenario(Path file) throws Exception {
        JsonNode root = JSON.readTree(Files.readAllBytes(file));
        List<SectionSeats> sections = new ArrayList<>();
        for (JsonNode node : root.get("sections")) {
            List<TimeSlot> slots = new ArrayList<>();
            for (JsonNode slot : node.get("slots")) {
                slots.add(TimeSlot.of(Weekday.valueOf(slot.get("day").asString().toUpperCase()),
                        minutes(slot.get("start").asString()), minutes(slot.get("end").asString())).orElseThrow());
            }
            Section section = new Section(new SectionId(node.get("id").asString()),
                    new CourseId(node.get("course").asString()), slots, node.get("capacity").asInt());
            sections.add(new SectionSeats(section,
                    node.has("available") ? node.get("available").asInt() : section.capacity()));
        }
        List<Course> courses = new ArrayList<>();
        for (JsonNode node : root.get("courses")) {
            courses.add(new Course(new CourseId(node.get("id").asString()), node.get("credits").asInt()));
        }
        CatalogLogEntry.Snapshot snapshot = new CatalogLogEntry.Snapshot(
                root.get("catalog_seq").asLong(), sections, courses,
                new EnrollmentRules(root.has("max_credits") ? root.get("max_credits").asInt() : 0));
        ObjectNode spec = JSON.createObjectNode();
        spec.put("file", "scenario.json");
        return new Catalog("scenario", snapshot, spec);
    }

    private static int minutes(String hhmm) {
        String[] parts = hhmm.split(":");
        return Integer.parseInt(parts[0]) * 60 + Integer.parseInt(parts[1]);
    }

    // ---- Perfiles de configuración ----------------------------------------------------------

    private record Profile(String name, Catalog catalog, boolean jwt, int maxCombinations, int maxAlternatives,
            RequestLimits limits) {
    }

    // ---- Casos ------------------------------------------------------------------------------

    private record Req(String name, String method, String path, List<String[]> headers, byte[] body,
            boolean chunked) {
    }

    private static final class Cases {
        private final List<Req> list = new ArrayList<>();

        Cases post(String name, String path, String body) {
            list.add(new Req(name, "POST", path, List.<String[]>of(new String[] {"Content-Type", "application/json"}),
                    body == null ? new byte[0] : body.getBytes(StandardCharsets.UTF_8), false));
            return this;
        }

        Cases postHeaders(String name, String path, String body, String... headerPairs) {
            List<String[]> headers = new ArrayList<>();
            for (int i = 0; i < headerPairs.length; i += 2) {
                headers.add(new String[] {headerPairs[i], headerPairs[i + 1]});
            }
            list.add(new Req(name, "POST", path, headers,
                    body == null ? new byte[0] : body.getBytes(StandardCharsets.UTF_8), false));
            return this;
        }

        Cases postBytes(String name, String path, byte[] body) {
            list.add(new Req(name, "POST", path, List.<String[]>of(new String[] {"Content-Type", "application/json"}),
                    body, false));
            return this;
        }

        Cases postChunked(String name, String path, String body, String... headerPairs) {
            List<String[]> headers = new ArrayList<>();
            headers.add(new String[] {"Content-Type", "application/json"});
            for (int i = 0; i < headerPairs.length; i += 2) {
                headers.add(new String[] {headerPairs[i], headerPairs[i + 1]});
            }
            list.add(new Req(name, "POST", path, headers, body.getBytes(StandardCharsets.UTF_8), true));
            return this;
        }

        Cases get(String name, String method, String path, String... headerPairs) {
            List<String[]> headers = new ArrayList<>();
            for (int i = 0; i < headerPairs.length; i += 2) {
                headers.add(new String[] {headerPairs[i], headerPairs[i + 1]});
            }
            list.add(new Req(name, method, path, headers, new byte[0], false));
            return this;
        }
    }

    private static String q(String s) {
        return JSON.writeValueAsString(s);
    }

    private static String student(String id, String... courses) {
        StringBuilder sb = new StringBuilder("{\"student_id\":" + q(id) + ",\"eligible_courses\":[");
        for (int i = 0; i < courses.length; i++) {
            sb.append(i > 0 ? "," : "").append(q(courses[i]));
        }
        return sb.append("]}").toString();
    }

    /** Un subconjunto determinista y repartido de las solicitudes de la prueba de carga. */
    private static void bulk(Cases cases, Path benchDir) throws Exception {
        JsonNode students = JSON.readTree(Files.readAllBytes(benchDir.resolve("students.json")));
        int withAvoid = 0;
        int without = 0;
        for (int i = 0; i < students.size(); i += 37) {
            JsonNode s = students.get(i);
            boolean avoid = s.has("avoid") && s.get("avoid").size() > 0;
            if (avoid ? withAvoid >= 160 : without >= 90) {
                continue;
            }
            if (avoid) {
                withAvoid++;
            } else {
                without++;
            }
            cases.post("students.json[" + i + "]" + (avoid ? " avoid" : ""), "/combinations", JSON.writeValueAsString(s));
        }
        JsonNode alternatives = JSON.readTree(Files.readAllBytes(benchDir.resolve("alternatives.json")));
        int taken = 0;
        for (int i = 0; i < alternatives.size() && taken < 200; i += 23) {
            cases.post("alternatives.json[" + i + "]", "/alternatives", JSON.writeValueAsString(alternatives.get(i)));
            taken++;
        }
    }

    private static Cases scenarioCases(Path benchDir) throws Exception {
        Cases c = new Cases();
        bulk(c, benchDir);
        // 12 cursos de 3 o más créditos superan el tope de 28.
        c.post("credits exceeded", "/combinations", student("e1", "C0001", "C0002", "C0003", "C0004", "C0005",
                "C0006", "C0007", "C0008", "C0009", "C0010", "C0011", "C0012"));
        c.post("credits exceeded alternatives", "/alternatives", "{\"student_id\":\"e1\",\"eligible_courses\":"
                + "[\"C0001\",\"C0002\",\"C0003\",\"C0004\",\"C0005\",\"C0006\",\"C0007\",\"C0008\",\"C0009\","
                + "\"C0010\",\"C0011\",\"C0012\"],\"selection\":{\"C0001\":\"C0001-00\"}}");
        c.post("repeated courses", "/combinations", student("e1", "C0004", "C0010", "C0004", "C0010"));
        c.post("unknown course", "/combinations", student("e1", "C0004", "NOPE"));
        c.post("course without seats", "/combinations", student("e1", "C0000"));
        c.post("a single course", "/combinations", student("e1", "C0007"));
        c.post("avoid every day", "/combinations", "{\"student_id\":\"e1\",\"eligible_courses\":[\"C0004\",\"C0010\"],"
                + "\"avoid\":[{\"day\":\"monday\",\"start_minute\":0,\"end_minute\":1440},"
                + "{\"day\":\"Tuesday\",\"start_minute\":0,\"end_minute\":1440},"
                + "{\"day\":\"WEDNESDAY\",\"start_minute\":0,\"end_minute\":1440}]}");
        c.post("avoid that penalizes nothing", "/combinations", "{\"student_id\":\"e1\",\"eligible_courses\":[\"C0004\"],"
                + "\"avoid\":[{\"day\":\"sunday\",\"start_minute\":0,\"end_minute\":60}]}");
        c.post("empty avoid", "/combinations", "{\"student_id\":\"e1\",\"eligible_courses\":[\"C0004\"],\"avoid\":[]}");
        c.post("null avoid", "/combinations", "{\"student_id\":\"e1\",\"eligible_courses\":[\"C0004\"],\"avoid\":null}");
        c.post("alternatives without selection", "/alternatives", student("e1", "C0004", "C0010", "C0028"));
        return c;
    }

    private static Cases wideCases() {
        Cases c = new Cases();
        c.post("200 results", "/combinations", student("w1", "C0004", "C0010"));
        c.post("200 results avoid", "/combinations", "{\"student_id\":\"w2\",\"eligible_courses\":[\"C0004\",\"C0010\"],"
                + "\"avoid\":[{\"day\":\"monday\",\"start_minute\":420,\"end_minute\":720}]}");
        c.post("200 results 3 courses", "/combinations", student("w3", "C0004", "C0010", "C0028"));
        return c;
    }

    private static final String DAY_OK = "{\"day\":\"monday\",\"start_minute\":420,\"end_minute\":540}";

    /** Casos de borde sobre el catálogo pequeño: validación, créditos, selección y lectura del cuerpo. */
    private static Cases miniCases() {
        Cases c = new Cases();
        String mf = "\"student_id\":\"s1\",\"eligible_courses\":[\"MAT\",\"FIS\"]";
        // Combinaciones normales
        c.post("mini: MAT+FIS", "/combinations", student("s1", "MAT", "FIS"));
        c.post("mini: MAT+FIS+QUI", "/combinations", student("s1", "MAT", "FIS", "QUI"));
        c.post("mini: no schedule (MAT FIS QUI clash)", "/combinations", student("s1", "FIS", "QUI"));
        c.post("mini: course without credits", "/combinations", student("s1", "HIS"));
        c.post("mini: credits equal to the limit", "/combinations", student("s1", "MAT", "FIS", "QUI", "ART"));
        c.post("mini: avoid monday", "/combinations", "{" + mf + ",\"avoid\":[{\"day\":\"monday\",\"start_minute\":0,\"end_minute\":1440}]}");
        c.post("mini: avoid touching only", "/combinations", "{" + mf + ",\"avoid\":[{\"day\":\"monday\",\"start_minute\":540,\"end_minute\":600}]}");
        c.post("mini: avoid day uppercase turkish", "/combinations", "{" + mf + ",\"avoid\":[{\"day\":\"MONDAY\",\"start_minute\":0,\"end_minute\":60}]}");
        // Avoid inválido
        c.post("avoid: empty window", "/combinations", "{" + mf + ",\"avoid\":[{\"day\":\"monday\",\"start_minute\":600,\"end_minute\":600}]}");
        c.post("avoid: reversed", "/combinations", "{" + mf + ",\"avoid\":[" + DAY_OK + ",{\"day\":\"monday\",\"start_minute\":700,\"end_minute\":600}]}");
        c.post("avoid: past the day", "/combinations", "{" + mf + ",\"avoid\":[{\"day\":\"monday\",\"start_minute\":0,\"end_minute\":1500}]}");
        c.post("avoid: negative start", "/combinations", "{" + mf + ",\"avoid\":[{\"day\":\"monday\",\"start_minute\":-1,\"end_minute\":10}]}");
        c.post("avoid: spanish day", "/combinations", "{" + mf + ",\"avoid\":[{\"day\":\"lunes\",\"start_minute\":420,\"end_minute\":540}]}");
        c.post("avoid: missing day", "/combinations", "{" + mf + ",\"avoid\":[{\"start_minute\":420,\"end_minute\":540}]}");
        c.post("avoid: null day", "/combinations", "{" + mf + ",\"avoid\":[{\"day\":null,\"start_minute\":420,\"end_minute\":540}]}");
        c.post("avoid: null window", "/combinations", "{" + mf + ",\"avoid\":[null]}");
        c.post("avoid: empty object", "/combinations", "{" + mf + ",\"avoid\":[{}]}");
        c.post("avoid: day with quote and accent", "/combinations", "{" + mf + ",\"avoid\":[{\"day\":\"mi\\\"\\u00e9\\n\\u0001x\\ud83d\\ude00\",\"start_minute\":1,\"end_minute\":2}]}");
        c.post("avoid: dotless i uppercases to FRIDAY", "/combinations", "{" + mf + ",\"avoid\":[{\"day\":\"fr\\u0131day\",\"start_minute\":1,\"end_minute\":2}]}");
        c.post("avoid: avoid is an object", "/combinations", "{" + mf + ",\"avoid\":{}}");
        c.post("avoid: avoid is a string", "/combinations", "{" + mf + ",\"avoid\":\"monday\"}");
        c.post("avoid: start as string number", "/combinations", "{" + mf + ",\"avoid\":[{\"day\":\"monday\",\"start_minute\":\"420\",\"end_minute\":540}]}");
        c.post("avoid: start as bad string", "/combinations", "{" + mf + ",\"avoid\":[{\"day\":\"monday\",\"start_minute\":\"x\",\"end_minute\":540}]}");
        c.post("avoid: start as float", "/combinations", "{" + mf + ",\"avoid\":[{\"day\":\"monday\",\"start_minute\":420.9,\"end_minute\":540.0}]}");
        c.post("avoid: start as exponent", "/combinations", "{" + mf + ",\"avoid\":[{\"day\":\"monday\",\"start_minute\":4.2e2,\"end_minute\":5.4E2}]}");
        c.post("avoid: start null", "/combinations", "{" + mf + ",\"avoid\":[{\"day\":\"monday\",\"start_minute\":null,\"end_minute\":540}]}");
        c.post("avoid: start missing", "/combinations", "{" + mf + ",\"avoid\":[{\"day\":\"monday\",\"end_minute\":540}]}");
        c.post("avoid: start bool", "/combinations", "{" + mf + ",\"avoid\":[{\"day\":\"monday\",\"start_minute\":true,\"end_minute\":540}]}");
        c.post("avoid: start huge", "/combinations", "{" + mf + ",\"avoid\":[{\"day\":\"monday\",\"start_minute\":99999999999,\"end_minute\":540}]}");
        c.post("avoid: start float huge", "/combinations", "{" + mf + ",\"avoid\":[{\"day\":\"monday\",\"start_minute\":1e20,\"end_minute\":540}]}");
        c.post("avoid: start empty string", "/combinations", "{" + mf + ",\"avoid\":[{\"day\":\"monday\",\"start_minute\":\"\",\"end_minute\":540}]}");
        c.post("avoid: start padded string", "/combinations", "{" + mf + ",\"avoid\":[{\"day\":\"monday\",\"start_minute\":\" 420 \",\"end_minute\":540}]}");
        c.post("avoid: start float string", "/combinations", "{" + mf + ",\"avoid\":[{\"day\":\"monday\",\"start_minute\":\"420.0\",\"end_minute\":540}]}");
        c.post("avoid: start plus string", "/combinations", "{" + mf + ",\"avoid\":[{\"day\":\"monday\",\"start_minute\":\"+420\",\"end_minute\":540}]}");
        c.post("avoid: start array", "/combinations", "{" + mf + ",\"avoid\":[{\"day\":\"monday\",\"start_minute\":[420],\"end_minute\":540}]}");
        c.post("avoid: day number", "/combinations", "{" + mf + ",\"avoid\":[{\"day\":5,\"start_minute\":420,\"end_minute\":540}]}");
        c.post("avoid: day object", "/combinations", "{" + mf + ",\"avoid\":[{\"day\":{},\"start_minute\":420,\"end_minute\":540}]}");
        c.post("avoid: unknown fields in window", "/combinations", "{" + mf + ",\"avoid\":[{\"day\":\"monday\",\"start_minute\":420,\"end_minute\":540,\"x\":1}]}");
        c.post("avoid: camel case names", "/combinations", "{" + mf + ",\"avoid\":[{\"day\":\"monday\",\"startMinute\":420,\"endMinute\":540}]}");
        c.post("avoid: duplicate keys in window", "/combinations", "{" + mf + ",\"avoid\":[{\"day\":\"monday\",\"day\":\"tuesday\",\"start_minute\":420,\"end_minute\":540}]}");
        c.post("avoid: first invalid wins over later", "/combinations", "{" + mf + ",\"avoid\":[{\"day\":\"x\",\"start_minute\":1,\"end_minute\":2},{\"day\":null}]}");
        // Identidad y ids
        c.post("student_id missing", "/combinations", "{\"eligible_courses\":[\"MAT\"]}");
        c.post("student_id null", "/combinations", "{\"student_id\":null,\"eligible_courses\":[\"MAT\"]}");
        c.post("student_id blank", "/combinations", "{\"student_id\":\"  \",\"eligible_courses\":[\"MAT\"]}");
        c.post("student_id empty", "/combinations", "{\"student_id\":\"\",\"eligible_courses\":[\"MAT\"]}");
        c.post("student_id nbsp", "/combinations", "{\"student_id\":\"\\u00a0\",\"eligible_courses\":[\"MAT\"]}");
        c.post("student_id unit separator", "/combinations", "{\"student_id\":\"\\u001f\",\"eligible_courses\":[\"MAT\"]}");
        c.post("student_id ideographic space", "/combinations", "{\"student_id\":\"\\u3000\",\"eligible_courses\":[\"MAT\"]}");
        c.post("student_id number", "/combinations", "{\"student_id\":1,\"eligible_courses\":[\"MAT\"]}");
        c.post("student_id float", "/combinations", "{\"student_id\":1.50,\"eligible_courses\":[\"MAT\"]}");
        c.post("student_id big exponent", "/combinations", "{\"student_id\":1e3,\"eligible_courses\":[\"MAT\"]}");
        c.post("student_id true", "/combinations", "{\"student_id\":true,\"eligible_courses\":[\"MAT\"]}");
        c.post("student_id array", "/combinations", "{\"student_id\":[\"s1\"],\"eligible_courses\":[\"MAT\"]}");
        c.post("student_id object", "/combinations", "{\"student_id\":{},\"eligible_courses\":[\"MAT\"]}");
        c.post("student_id with space", "/combinations", "{\"student_id\":\"a b\",\"eligible_courses\":[\"MAT\"]}");
        c.post("student_id accent", "/combinations", "{\"student_id\":\"\\u00f1\",\"eligible_courses\":[\"MAT\"]}");
        c.post("student_id 65 chars", "/combinations", "{\"student_id\":\"" + "a".repeat(65) + "\",\"eligible_courses\":[\"MAT\"]}");
        c.post("student_id 64 chars", "/combinations", "{\"student_id\":\"" + "a".repeat(64) + "\",\"eligible_courses\":[\"MAT\"]}");
        c.post("student_id 33 emoji (66 utf-16 units)", "/combinations", "{\"student_id\":\"" + "\\ud83d\\ude00".repeat(33) + "\",\"eligible_courses\":[\"MAT\"]}");
        c.post("student_id 32 emoji", "/combinations", "{\"student_id\":\"" + "\\ud83d\\ude00".repeat(32) + "\",\"eligible_courses\":[\"MAT\"]}");
        c.post("student_id newline", "/combinations", "{\"student_id\":\"a\\n\",\"eligible_courses\":[\"MAT\"]}");
        c.post("eligible_courses missing", "/combinations", "{\"student_id\":\"s1\"}");
        c.post("eligible_courses null", "/combinations", "{\"student_id\":\"s1\",\"eligible_courses\":null}");
        c.post("eligible_courses empty", "/combinations", "{\"student_id\":\"s1\",\"eligible_courses\":[]}");
        c.post("eligible_courses string", "/combinations", "{\"student_id\":\"s1\",\"eligible_courses\":\"MAT\"}");
        c.post("eligible_courses object", "/combinations", "{\"student_id\":\"s1\",\"eligible_courses\":{}}");
        c.post("eligible_courses null element", "/combinations", "{\"student_id\":\"s1\",\"eligible_courses\":[\"MAT\",null]}");
        c.post("eligible_courses number element", "/combinations", "{\"student_id\":\"s1\",\"eligible_courses\":[\"MAT\",7]}");
        c.post("eligible_courses bool element", "/combinations", "{\"student_id\":\"s1\",\"eligible_courses\":[true]}");
        c.post("eligible_courses array element", "/combinations", "{\"student_id\":\"s1\",\"eligible_courses\":[[\"MAT\"]]}");
        c.post("eligible_courses empty element", "/combinations", "{\"student_id\":\"s1\",\"eligible_courses\":[\"\"]}");
        c.post("eligible_courses bad char element", "/combinations", "{\"student_id\":\"s1\",\"eligible_courses\":[\"MAT\",\"F/S\"]}");
        c.post("eligible_courses 13 items", "/combinations", "{\"student_id\":\"s1\",\"eligible_courses\":[\"A\",\"B\",\"C\",\"D\",\"E\",\"F\",\"G\",\"H\",\"I\",\"J\",\"K\",\"L\",\"M\"]}");
        c.post("eligible_courses 12 items (unknown)", "/combinations", "{\"student_id\":\"s1\",\"eligible_courses\":[\"A\",\"B\",\"C\",\"D\",\"E\",\"F\",\"G\",\"H\",\"I\",\"J\",\"K\",\"L\"]}");
        c.post("eligible_courses 13 repeated", "/combinations", "{\"student_id\":\"s1\",\"eligible_courses\":[\"A\",\"A\",\"A\",\"A\",\"A\",\"A\",\"A\",\"A\",\"A\",\"A\",\"A\",\"A\",\"A\"]}");
        c.post("avoid 43 windows", "/combinations", "{" + mf + ",\"avoid\":[" + (DAY_OK + ",").repeat(42) + DAY_OK + "]}");
        c.post("avoid 42 windows", "/combinations", "{" + mf + ",\"avoid\":[" + (DAY_OK + ",").repeat(41) + DAY_OK + "]}");
        c.post("check order: student before courses", "/combinations", "{\"student_id\":\"a b\",\"eligible_courses\":null}");
        c.post("check order: courses before avoid", "/combinations", "{\"student_id\":\"s1\",\"eligible_courses\":[],\"avoid\":[{}]}");
        c.post("check order: avoid before catalog 422", "/combinations", "{\"student_id\":\"s1\",\"eligible_courses\":[\"MAT\",\"FIS\",\"QUI\",\"ART\"],\"avoid\":[{}]}");
        // Créditos
        c.post("credits: just below limit", "/combinations", student("s1", "MAT", "FIS", "QUI"));
        c.post("credits: exceeds (MAT FIS QUI ART + HIS)", "/combinations", student("s1", "MAT", "FIS", "QUI", "ART", "HIS"));
        // Lectura del cuerpo
        c.post("body: empty", "/combinations", "");
        c.post("body: whitespace", "/combinations", "   ");
        c.post("body: null", "/combinations", "null");
        c.post("body: array", "/combinations", "[]");
        c.post("body: string", "/combinations", "\"hola\"");
        c.post("body: number", "/combinations", "5");
        c.post("body: true", "/combinations", "true");
        c.post("body: empty object", "/combinations", "{}");
        c.post("body: truncated", "/combinations", "{\"student_id\": ");
        c.post("body: not json", "/combinations", "no es json");
        c.post("body: trailing garbage", "/combinations", student("s1", "MAT") + " basura");
        c.post("body: two objects", "/combinations", student("s1", "MAT") + student("s2", "FIS"));
        c.post("body: trailing comma", "/combinations", "{\"student_id\":\"s1\",\"eligible_courses\":[\"MAT\"],}");
        c.post("body: single quotes", "/combinations", "{'student_id':'s1','eligible_courses':['MAT']}");
        c.post("body: unquoted names", "/combinations", "{student_id:\"s1\",eligible_courses:[\"MAT\"]}");
        c.post("body: comment", "/combinations", "{/*x*/\"student_id\":\"s1\",\"eligible_courses\":[\"MAT\"]}");
        c.post("body: NaN", "/combinations", "{\"student_id\":NaN,\"eligible_courses\":[\"MAT\"]}");
        c.post("body: leading zero", "/combinations", "{\"student_id\":\"s1\",\"eligible_courses\":[\"MAT\"],\"x\":01}");
        c.post("body: bom", "/combinations", "\ufeff" + student("s1", "MAT"));
        c.postBytes("body: invalid utf-8", "/combinations", new byte[] {'{', '"', 's', 't', 'u', 'd', 'e', 'n', 't', '_', 'i', 'd', '"', ':', '"', (byte) 0xff, '"', '}'});
        c.post("body: unescaped control char", "/combinations", "{\"student_id\":\"a\tb\",\"eligible_courses\":[\"MAT\"]}");
        c.post("body: bad escape", "/combinations", "{\"student_id\":\"a\\qb\",\"eligible_courses\":[\"MAT\"]}");
        c.post("body: lone surrogate escape", "/combinations", "{\"student_id\":\"a\\ud800\",\"eligible_courses\":[\"MAT\"]}");
        c.post("body: unknown fields", "/combinations", "{\"student_id\":\"s1\",\"eligible_courses\":[\"MAT\"],\"extra\":{\"a\":[1,2,{}]},\"zzz\":null}");
        c.post("body: camel case names", "/combinations", "{\"studentId\":\"s1\",\"eligibleCourses\":[\"MAT\"]}");
        c.post("body: duplicate keys", "/combinations", "{\"student_id\":\"x y\",\"student_id\":\"s1\",\"eligible_courses\":[\"MAT\"]}");
        c.post("body: case differing names", "/combinations", "{\"Student_Id\":\"s1\",\"eligible_courses\":[\"MAT\"]}");
        c.post("body: deep nesting in unknown field (200)", "/combinations", "{\"student_id\":\"s1\",\"eligible_courses\":[\"MAT\"],\"x\":" + "[".repeat(200) + "]".repeat(200) + "}");
        c.post("body: deep nesting in unknown field (600)", "/combinations", "{\"student_id\":\"s1\",\"eligible_courses\":[\"MAT\"],\"x\":" + "[".repeat(600) + "]".repeat(600) + "}");
        c.post("body: huge number in unknown field", "/combinations", "{\"student_id\":\"s1\",\"eligible_courses\":[\"MAT\"],\"x\":" + "9".repeat(1100) + "}");
        c.post("body: big number ok", "/combinations", "{\"student_id\":\"s1\",\"eligible_courses\":[\"MAT\"],\"x\":" + "9".repeat(900) + "}");
        c.post("body: very long key", "/combinations", "{\"student_id\":\"s1\",\"eligible_courses\":[\"MAT\"],\"" + "k".repeat(50_001) + "\":1}");
        c.post("body: key 50000", "/combinations", "{\"student_id\":\"s1\",\"eligible_courses\":[\"MAT\"],\"" + "k".repeat(50_000) + "\":1}");
        c.post("body: escaped name", "/combinations", "{\"student\\u005fid\":\"s1\",\"eligible_courses\":[\"MAT\"]}");
        c.post("body: tab whitespace", "/combinations", "\t{\r\n\"student_id\" :\t\"s1\" ,\"eligible_courses\":[ \"MAT\" ]\n}\n");
        c.post("body: form feed whitespace", "/combinations", "\f" + student("s1", "MAT"));
        c.post("body: vertical tab whitespace", "/combinations", student("s1", "MAT").replace(",", ",\u000b"));
        c.post("body: nul", "/combinations", student("s1", "MAT").replace(",", ",\u0000"));
        // Content-Type
        c.postHeaders("content-type: text/plain", "/combinations", student("s1", "MAT"), "Content-Type", "text/plain");
        c.postHeaders("content-type: absent with body", "/combinations", student("s1", "MAT"));
        c.postHeaders("content-type: absent no body", "/combinations", "");
        c.postHeaders("content-type: json charset", "/combinations", student("s1", "MAT"), "Content-Type", "application/json; charset=utf-8");
        c.postHeaders("content-type: json upper", "/combinations", student("s1", "MAT"), "Content-Type", "APPLICATION/JSON");
        c.postHeaders("content-type: problem+json", "/combinations", student("s1", "MAT"), "Content-Type", "application/problem+json");
        c.postHeaders("content-type: json latin1", "/combinations", student("s1", "MAT"), "Content-Type", "application/json; charset=iso-8859-1");
        c.postHeaders("content-type: form", "/combinations", "a=b", "Content-Type", "application/x-www-form-urlencoded");
        c.postHeaders("content-type: invalid", "/combinations", student("s1", "MAT"), "Content-Type", "no-es-un-tipo");
        c.postHeaders("content-type: text/json", "/combinations", student("s1", "MAT"), "Content-Type", "text/json");
        c.postHeaders("accept: xml", "/combinations", student("s1", "MAT"), "Content-Type", "application/json", "Accept", "application/xml");
        c.postHeaders("accept: json", "/combinations", student("s1", "MAT"), "Content-Type", "application/json", "Accept", "application/json");
        c.postHeaders("accept: text/plain", "/combinations", student("s1", "MAT"), "Content-Type", "application/json", "Accept", "text/plain");
        // Métodos y rutas
        c.get("GET /combinations", "GET", "/combinations");
        c.get("PUT /combinations", "PUT", "/combinations");
        c.get("DELETE /combinations", "DELETE", "/combinations");
        c.get("GET /alternatives", "GET", "/alternatives");
        c.get("POST /health", "POST", "/health");
        c.get("HEAD /health", "HEAD", "/health");
        c.get("GET /health/", "GET", "/health/");
        c.get("GET /health.json", "GET", "/health.json");
        c.get("GET /ready", "GET", "/ready");
        c.get("HEAD /ready", "HEAD", "/ready");
        c.get("POST /ready", "POST", "/ready");
        c.get("GET /nope", "GET", "/nope");
        c.get("GET /", "GET", "/");
        c.get("GET /combinations/", "GET", "/combinations/");
        c.get("POST /combinations/", "POST", "/combinations/");
        c.get("GET /combinations.json", "GET", "/combinations.json");
        c.get("GET /health?x=1", "GET", "/health?x=1");
        c.post("POST /combinations?x=1", "/combinations?x=1", student("s1", "MAT"));
        c.post("POST //combinations", "//combinations", student("s1", "MAT"));
        c.post("POST /combinations;a=1", "/combinations;a=1", student("s1", "MAT"));
        c.post("POST /%63ombinations", "/%63ombinations", student("s1", "MAT"));
        c.post("POST /nope", "/nope", "{}");
        c.get("OPTIONS /combinations", "OPTIONS", "/combinations");
        c.get("OPTIONS /health", "OPTIONS", "/health");
        c.get("GET /favicon.ico", "GET", "/favicon.ico");
        c.get("GET /actuator/health", "GET", "/actuator/health");
        // Cliente web
        c.get("GET /app", "GET", "/app");
        c.get("GET /app/", "GET", "/app/");
        c.get("HEAD /app/", "HEAD", "/app/");
        c.get("HEAD /app", "HEAD", "/app");
        c.get("GET /app/index.html", "GET", "/app/index.html");
        c.get("GET /app/styles.css", "GET", "/app/styles.css");
        c.get("GET /app/favicon.svg", "GET", "/app/favicon.svg");
        c.get("GET /app/js/main.js", "GET", "/app/js/main.js");
        c.get("GET /app/js/logic.js", "GET", "/app/js/logic.js");
        c.get("GET /app/nope.js", "GET", "/app/nope.js");
        c.get("GET /app/js/", "GET", "/app/js/");
        c.get("GET /app/js", "GET", "/app/js");
        c.get("GET /app//", "GET", "/app//");
        c.get("GET /app/../health", "GET", "/app/../health");
        c.get("GET /app/%2e%2e/health", "GET", "/app/%2e%2e/health");
        c.get("GET /app/index.html;x", "GET", "/app/index.html;x");
        c.get("GET /app?x=1", "GET", "/app?x=1");
        c.get("GET /app/?x=1", "GET", "/app/?x=1");
        c.get("GET /app/index.html?v=1", "GET", "/app/index.html?v=1");
        c.get("GET /appx", "GET", "/appx");
        c.get("GET /apps/x", "GET", "/apps/x");
        c.get("GET /application", "GET", "/application");
        c.get("POST /app/index.html", "POST", "/app/index.html");
        c.get("POST /app", "POST", "/app");
        c.get("PUT /app/", "PUT", "/app/");
        c.get("OPTIONS /app/", "OPTIONS", "/app/");
        c.get("GET /app/ with request id", "GET", "/app/", "X-Request-Id", "abc-123");
        c.get("GET /app/index.html if-none-match", "GET", "/app/index.html", "If-None-Match", "\"x\"");
        // Id de correlación
        for (String id : new String[] {"abc-123_X.9", "a".repeat(64), "a".repeat(65), "con espacios", "", "a;b", "<script>", "ok.1"}) {
            c.get("request id [" + id.replace("\n", "\\n") + "]", "GET", "/health", "X-Request-Id", id);
        }
        c.postHeaders("request id on 400", "/combinations", "no es json", "Content-Type", "application/json", "X-Request-Id", "req-400");
        c.postHeaders("request id on 422", "/combinations", student("s1", "MAT", "FIS", "QUI", "ART", "HIS"), "Content-Type", "application/json", "X-Request-Id", "req-422");
        c.get("request id on 404", "GET", "/nope", "X-Request-Id", "req-404");
        return c;
    }

    /** Selección, inscritos, cupos y cruces (POST /alternatives) sobre el catálogo pequeño. */
    private static Cases alternativesCases() {
        Cases c = new Cases();
        String mf = "\"student_id\":\"s1\",\"eligible_courses\":[\"MAT\",\"FIS\"]";
        c.post("alt: selection valid", "/alternatives", "{" + mf + ",\"selection\":{\"MAT\":\"MAT-02\",\"FIS\":\"FIS-01\"}}");
        c.post("alt: selection MAT-01 without seats", "/alternatives", "{" + mf + ",\"selection\":{\"MAT\":\"MAT-01\",\"FIS\":\"FIS-01\"}}");
        c.post("alt: enrolled MAT-01 without seats", "/alternatives", "{" + mf + ",\"selection\":{\"MAT\":\"MAT-01\",\"FIS\":\"FIS-01\"},\"enrolled\":[\"MAT-01\"]}");
        c.post("alt: enrolled with seats", "/alternatives", "{" + mf + ",\"selection\":{\"MAT\":\"MAT-02\",\"FIS\":\"FIS-01\"},\"enrolled\":[\"MAT-02\",\"FIS-01\"]}");
        c.post("alt: enrolled both without seats courses", "/alternatives", "{\"student_id\":\"s1\",\"eligible_courses\":[\"FIS\",\"QUI\",\"MAT\"],\"selection\":{\"QUI\":\"QUI-02\",\"MAT\":\"MAT-01\",\"FIS\":\"FIS-01\"},\"enrolled\":[\"QUI-02\",\"MAT-01\"]}");
        c.post("alt: enrolled not in selection", "/alternatives", "{" + mf + ",\"selection\":{\"FIS\":\"FIS-01\"},\"enrolled\":[\"MAT-01\"]}");
        c.post("alt: several enrolled not in selection", "/alternatives", "{\"student_id\":\"s1\",\"eligible_courses\":[\"MAT\",\"FIS\",\"QUI\",\"HIS\"],\"selection\":{\"HIS\":\"HIS-01\"},\"enrolled\":[\"MAT-01\",\"FIS-01\",\"QUI-01\",\"ZZZ-9\",\"AAA-1\",\"Aa\",\"BB\"]}");
        c.post("alt: several selection courses not requested", "/alternatives", "{\"student_id\":\"s1\",\"eligible_courses\":[\"MAT\"],\"selection\":{\"QUI\":\"QUI-01\",\"FIS\":\"FIS-01\",\"HIS\":\"HIS-01\",\"Aa\":\"x\",\"BB\":\"y\",\"MAT\":\"MAT-02\"}}");
        c.post("alt: enrolled duplicates", "/alternatives", "{" + mf + ",\"selection\":{\"MAT\":\"MAT-01\"},\"enrolled\":[\"MAT-01\",\"MAT-01\"]}");
        c.post("alt: enrolled group of another course", "/alternatives", "{" + mf + ",\"selection\":{\"MAT\":\"FIS-01\"},\"enrolled\":[\"FIS-01\"]}");
        c.post("alt: group of another course", "/alternatives", "{" + mf + ",\"selection\":{\"MAT\":\"FIS-01\"}}");
        c.post("alt: group of another course in the second course", "/alternatives", "{" + mf + ",\"selection\":{\"MAT\":\"MAT-02\",\"FIS\":\"MAT-03\"}}");
        c.post("alt: selection nonexistent group", "/alternatives", "{" + mf + ",\"selection\":{\"MAT\":\"NOPE\",\"FIS\":\"FIS-01\"}}");
        c.post("alt: selection clash", "/alternatives", "{" + mf + ",\"selection\":{\"MAT\":\"MAT-03\",\"FIS\":\"FIS-02\"}}");
        c.post("alt: clash plus no seats", "/alternatives", "{\"student_id\":\"s1\",\"eligible_courses\":[\"MAT\",\"FIS\",\"QUI\"],\"selection\":{\"MAT\":\"MAT-03\",\"FIS\":\"FIS-02\",\"QUI\":\"QUI-02\"}}");
        c.post("alt: no seats prevails over clash", "/alternatives", "{\"student_id\":\"s1\",\"eligible_courses\":[\"MAT\",\"FIS\"],\"selection\":{\"MAT\":\"MAT-01\",\"FIS\":\"FIS-02\"}}");
        c.post("alt: clash FIS-01 with QUI-01", "/alternatives", "{\"student_id\":\"s1\",\"eligible_courses\":[\"FIS\",\"QUI\"],\"selection\":{\"FIS\":\"FIS-01\",\"QUI\":\"QUI-01\"}}");
        c.post("alt: partial selection", "/alternatives", "{" + mf + ",\"selection\":{\"FIS\":\"FIS-01\"}}");
        c.post("alt: no selection field", "/alternatives", "{" + mf + "}");
        c.post("alt: selection null", "/alternatives", "{" + mf + ",\"selection\":null}");
        c.post("alt: selection empty", "/alternatives", "{" + mf + ",\"selection\":{}}");
        c.post("alt: selection array", "/alternatives", "{" + mf + ",\"selection\":[1]}");
        c.post("alt: selection string", "/alternatives", "{" + mf + ",\"selection\":\"MAT\"}");
        c.post("alt: selection value null", "/alternatives", "{" + mf + ",\"selection\":{\"MAT\":null}}");
        c.post("alt: selection value number", "/alternatives", "{" + mf + ",\"selection\":{\"MAT\":5}}");
        c.post("alt: selection value blank", "/alternatives", "{" + mf + ",\"selection\":{\"MAT\":\" \"}}");
        c.post("alt: selection value empty", "/alternatives", "{" + mf + ",\"selection\":{\"MAT\":\"\"}}");
        c.post("alt: selection value bad char", "/alternatives", "{" + mf + ",\"selection\":{\"MAT\":\"MAT 01\"}}");
        c.post("alt: selection value long", "/alternatives", "{" + mf + ",\"selection\":{\"MAT\":\"" + "G".repeat(65) + "\"}}");
        c.post("alt: selection key empty", "/alternatives", "{" + mf + ",\"selection\":{\"\":\"MAT-01\"}}");
        c.post("alt: selection key bad", "/alternatives", "{" + mf + ",\"selection\":{\"M/T\":\"MAT-01\"}}");
        c.post("alt: selection duplicated key", "/alternatives", "{" + mf + ",\"selection\":{\"MAT\":\"MAT-01\",\"FIS\":\"FIS-01\",\"MAT\":\"MAT-02\"}}");
        c.post("alt: selection first invalid in order", "/alternatives", "{" + mf + ",\"selection\":{\"FIS\":\"FIS 01\",\"MAT\":\"x y\"}}");
        c.post("alt: selection invalid key before value", "/alternatives", "{" + mf + ",\"selection\":{\"M T\":\"x y\"}}");
        c.post("alt: selection 13 entries", "/alternatives", "{" + mf + ",\"selection\":{" + "\"A\":\"a\",\"B\":\"a\",\"C\":\"a\",\"D\":\"a\",\"E\":\"a\",\"F\":\"a\",\"G\":\"a\",\"H\":\"a\",\"I\":\"a\",\"J\":\"a\",\"K\":\"a\",\"L\":\"a\",\"M\":\"a\"}}");
        c.post("alt: enrolled null", "/alternatives", "{" + mf + ",\"enrolled\":null}");
        c.post("alt: enrolled string", "/alternatives", "{" + mf + ",\"enrolled\":\"MAT-01\"}");
        c.post("alt: enrolled object", "/alternatives", "{" + mf + ",\"enrolled\":{}}");
        c.post("alt: enrolled null element", "/alternatives", "{" + mf + ",\"enrolled\":[null]}");
        c.post("alt: enrolled blank element", "/alternatives", "{" + mf + ",\"selection\":{\"MAT\":\"MAT-01\"},\"enrolled\":[\" \"]}");
        c.post("alt: enrolled 13", "/alternatives", "{" + mf + ",\"enrolled\":[\"a\",\"b\",\"c\",\"d\",\"e\",\"f\",\"g\",\"h\",\"i\",\"j\",\"k\",\"l\",\"m\"]}");
        c.post("alt: invalid selection before enrolled check", "/alternatives", "{" + mf + ",\"selection\":{\"MAT\":\"x y\"},\"enrolled\":[\"q\"]}");
        c.post("alt: invalid avoid", "/alternatives", "{" + mf + ",\"avoid\":[{\"day\":\"x\"}]}");
        c.post("alt: avoid and selection", "/alternatives", "{" + mf + ",\"avoid\":[{\"day\":\"wednesday\",\"start_minute\":0,\"end_minute\":1440}],\"selection\":{\"MAT\":\"MAT-02\",\"FIS\":\"FIS-01\"}}");
        c.post("alt: avoid prefers other group", "/alternatives", "{" + mf + ",\"avoid\":[{\"day\":\"tuesday\",\"start_minute\":0,\"end_minute\":1440}],\"selection\":{\"MAT\":\"MAT-02\",\"FIS\":\"FIS-01\"}}");
        c.post("alt: avoid penalty zero but present", "/alternatives", "{" + mf + ",\"avoid\":[{\"day\":\"sunday\",\"start_minute\":0,\"end_minute\":60}],\"selection\":{\"MAT\":\"MAT-02\",\"FIS\":\"FIS-01\"}}");
        c.post("alt: course without groups with seats", "/alternatives", "{\"student_id\":\"s1\",\"eligible_courses\":[\"ART\"],\"selection\":{\"ART\":\"ART-01\"}}");
        c.post("alt: enrolled group without seats, no other groups", "/alternatives", "{\"student_id\":\"s1\",\"eligible_courses\":[\"ART\"],\"selection\":{\"ART\":\"ART-01\"},\"enrolled\":[\"ART-01\"]}");
        c.post("alt: credits exceeded", "/alternatives", "{\"student_id\":\"s1\",\"eligible_courses\":[\"MAT\",\"FIS\",\"QUI\",\"ART\",\"HIS\"],\"selection\":{\"MAT\":\"MAT-02\"}}");
        c.post("alt: credits exceeded wins over invalid selection of group", "/alternatives", "{\"student_id\":\"s1\",\"eligible_courses\":[\"MAT\",\"FIS\",\"QUI\",\"ART\",\"HIS\"],\"selection\":{\"MAT\":\"FIS-01\"}}");
        c.post("alt: not eligible selection before credits", "/alternatives", "{\"student_id\":\"s1\",\"eligible_courses\":[\"MAT\",\"FIS\",\"QUI\",\"ART\",\"HIS\"],\"selection\":{\"ZZZ\":\"a\"}}");
        c.post("alt: repeated courses", "/alternatives", "{\"student_id\":\"s1\",\"eligible_courses\":[\"MAT\",\"FIS\",\"MAT\"],\"selection\":{\"MAT\":\"MAT-02\"}}");
        c.post("alt: order of courses", "/alternatives", "{\"student_id\":\"s1\",\"eligible_courses\":[\"FIS\",\"MAT\"],\"selection\":{\"MAT\":\"MAT-01\",\"FIS\":\"FIS-01\"},\"enrolled\":[\"MAT-01\"]}");
        c.post("alt: empty courses", "/alternatives", "{\"student_id\":\"s1\",\"eligible_courses\":[]}");
        c.post("alt: body null", "/alternatives", "null");
        c.post("alt: body empty", "/alternatives", "");
        c.post("alt: unknown fields", "/alternatives", "{" + mf + ",\"junk\":1,\"selection\":{\"MAT\":\"MAT-02\"}}");
        c.post("alt: selection nested object value", "/alternatives", "{" + mf + ",\"selection\":{\"MAT\":{\"a\":1}}}");
        c.post("alt: selection value bool", "/alternatives", "{" + mf + ",\"selection\":{\"MAT\":true}}");
        c.post("alt: selection value float", "/alternatives", "{" + mf + ",\"selection\":{\"MAT\":1.5}}");
        return c;
    }

    private static Cases tightCases() {
        Cases c = new Cases();
        // Límites pequeños: 3 cursos, 2 ventanas, ids de 8 caracteres, 256 bytes.
        String w = "{\"day\":\"monday\",\"start_minute\":360,\"end_minute\":420}";
        c.post("tight: 3 courses ok", "/combinations", student("12345678", "MAT", "FIS", "QUI"));
        c.post("tight: 4 courses", "/combinations", student("s1", "MAT", "FIS", "QUI", "ART"));
        c.post("tight: id 9 chars", "/combinations", student("123456789", "MAT"));
        c.post("tight: course id 9 chars", "/combinations", student("s1", "ABCDEFGHI"));
        c.post("tight: avoid 2 ok", "/combinations", "{\"student_id\":\"s1\",\"eligible_courses\":[\"MAT\"],\"avoid\":[" + w + "," + w + "]}");
        c.post("tight: avoid 3", "/combinations", "{\"student_id\":\"s1\",\"eligible_courses\":[\"MAT\"],\"avoid\":[" + w + "," + w + "," + w + "]}");
        c.post("tight: alt selection 4", "/alternatives", "{\"student_id\":\"s1\",\"eligible_courses\":[\"MAT\"],\"selection\":{\"A\":\"a\",\"B\":\"a\",\"C\":\"a\",\"D\":\"a\"}}");
        c.post("tight: alt enrolled 4", "/alternatives", "{\"student_id\":\"s1\",\"eligible_courses\":[\"MAT\"],\"enrolled\":[\"a\",\"b\",\"c\",\"d\"]}");
        c.post("tight: alt selection 3 ok", "/alternatives", "{\"student_id\":\"s1\",\"eligible_courses\":[\"MAT\",\"FIS\",\"QUI\"],\"selection\":{\"MAT\":\"MAT-02\",\"FIS\":\"FIS-01\",\"QUI\":\"QUI-01\"}}");
        // Cuerpo: 256 bytes exactos pasan, 257 no.
        String base = "{\"student_id\":\"s1\",\"eligible_courses\":[\"MAT\"],\"pad\":\"";
        String tail = "\"}";
        int padding = 256 - (base.length() + tail.length());
        c.post("tight: body exactly at the limit", "/combinations", base + "p".repeat(padding) + tail);
        c.post("tight: body one over the limit", "/combinations", base + "p".repeat(padding + 1) + tail);
        c.post("tight: body far over the limit", "/combinations", base + "p".repeat(5000) + tail);
        c.postChunked("tight: chunked at the limit", "/combinations", base + "p".repeat(padding) + tail);
        c.postChunked("tight: chunked over the limit", "/combinations", base + "p".repeat(padding + 1) + tail);
        c.postChunked("tight: chunked over the limit, invalid json", "/combinations", "x".repeat(300));
        c.postChunked("tight: chunked small", "/combinations", student("s1", "MAT"));
        c.postChunked("tight: chunked over the limit on alternatives", "/alternatives", base + "p".repeat(400) + tail);
        c.post("tight: body over the limit on unknown path", "/nope", "x".repeat(300));
        c.post("tight: body over the limit on /health", "/health", "x".repeat(300));
        c.postHeaders("tight: body over the limit, request id", "/combinations", "x".repeat(300), "Content-Type", "application/json", "X-Request-Id", "big-1");
        c.postHeaders("tight: body over the limit, wrong content type", "/combinations", "x".repeat(300), "Content-Type", "text/plain");
        c.postBytes("tight: multibyte body at the limit counts bytes", "/combinations",
                (base + "\u00f1".repeat(100) + tail).getBytes(StandardCharsets.UTF_8));
        c.postBytes("tight: multibyte body over the limit counts bytes", "/combinations",
                (base + "\u00f1".repeat(130) + tail).getBytes(StandardCharsets.UTF_8));
        return c;
    }

    // ---- JWT ---------------------------------------------------------------------------------

    private static String b64(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String sign(String headerJson, String payloadJson) {
        String input = b64(headerJson.getBytes(StandardCharsets.UTF_8)) + "."
                + b64(payloadJson.getBytes(StandardCharsets.UTF_8));
        byte[] sig = new byte[Ed25519.SIGNATURE_SIZE];
        byte[] message = input.getBytes(StandardCharsets.US_ASCII);
        byte[] publicKey = new byte[Ed25519.PUBLIC_KEY_SIZE];
        Ed25519.generatePublicKey(TEST_SEED, 0, publicKey, 0);
        Ed25519.sign(TEST_SEED, 0, publicKey, 0, null, message, 0, message.length, sig, 0);
        return input + "." + b64(sig);
    }

    private static String publicKeyPem() {
        byte[] publicKey = new byte[Ed25519.PUBLIC_KEY_SIZE];
        Ed25519.generatePublicKey(TEST_SEED, 0, publicKey, 0);
        byte[] prefix = {0x30, 0x2a, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x70, 0x03, 0x21, 0x00};
        byte[] der = new byte[prefix.length + publicKey.length];
        System.arraycopy(prefix, 0, der, 0, prefix.length);
        System.arraycopy(publicKey, 0, der, prefix.length, publicKey.length);
        return "-----BEGIN PUBLIC KEY-----\n"
                + Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII)).encodeToString(der)
                + "\n-----END PUBLIC KEY-----\n";
    }

    private static java.security.PublicKey publicKey() throws Exception {
        byte[] publicKey = new byte[Ed25519.PUBLIC_KEY_SIZE];
        Ed25519.generatePublicKey(TEST_SEED, 0, publicKey, 0);
        byte[] prefix = {0x30, 0x2a, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x70, 0x03, 0x21, 0x00};
        byte[] der = new byte[prefix.length + publicKey.length];
        System.arraycopy(prefix, 0, der, 0, prefix.length);
        System.arraycopy(publicKey, 0, der, prefix.length, publicKey.length);
        return java.security.KeyFactory.getInstance("Ed25519")
                .generatePublic(new java.security.spec.X509EncodedKeySpec(der));
    }

    private static final String HEADER = "{\"alg\":\"EdDSA\",\"typ\":\"JWT\",\"kid\":\"k1\"}";

    private static String claims(String sub, String coursesJson) {
        return "{\"iss\":\"" + ISSUER + "\",\"aud\":\"" + AUDIENCE + "\",\"sub\":" + q(sub) + ",\"courses\":"
                + coursesJson + ",\"iat\":" + NOW.getEpochSecond() + ",\"exp\":" + (NOW.getEpochSecond() + 600) + "}";
    }

    private static String token(String sub, String coursesJson) {
        return sign(HEADER, claims(sub, coursesJson));
    }

    private static Cases jwtCases() {
        Cases c = new Cases();
        String ok = token("est-1", "[\"MAT\",\"FIS\"]");
        String bearer = "Bearer " + ok;
        c.postHeaders("jwt: valid, empty body", "/combinations", "{}", "Content-Type", "application/json", "Authorization", bearer);
        c.postHeaders("jwt: valid, body ignored fields", "/combinations", "{\"avoid\":[" + DAY_OK + "]}", "Content-Type", "application/json", "Authorization", bearer);
        c.postHeaders("jwt: valid, matching body", "/combinations", "{\"student_id\":\"est-1\",\"eligible_courses\":[\"FIS\",\"MAT\"]}", "Content-Type", "application/json", "Authorization", bearer);
        c.postHeaders("jwt: valid, matching body with repeats", "/combinations", "{\"student_id\":\"est-1\",\"eligible_courses\":[\"FIS\",\"MAT\",\"MAT\"]}", "Content-Type", "application/json", "Authorization", bearer);
        c.postHeaders("jwt: body student differs", "/combinations", "{\"student_id\":\"otro\"}", "Content-Type", "application/json", "Authorization", bearer);
        c.postHeaders("jwt: body courses differ", "/combinations", "{\"eligible_courses\":[\"MAT\"]}", "Content-Type", "application/json", "Authorization", bearer);
        c.postHeaders("jwt: body courses superset", "/combinations", "{\"eligible_courses\":[\"MAT\",\"FIS\",\"QUI\"]}", "Content-Type", "application/json", "Authorization", bearer);
        c.postHeaders("jwt: body student invalid but token ok", "/combinations", "{\"student_id\":\"a b\"}", "Content-Type", "application/json", "Authorization", bearer);
        c.postHeaders("jwt: body student null", "/combinations", "{\"student_id\":null,\"eligible_courses\":null}", "Content-Type", "application/json", "Authorization", bearer);
        c.postHeaders("jwt: body courses elements invalid", "/combinations", "{\"eligible_courses\":[\"MAT\",null]}", "Content-Type", "application/json", "Authorization", bearer);
        c.postHeaders("jwt: body avoid invalid", "/combinations", "{\"avoid\":[{\"day\":\"x\"}]}", "Content-Type", "application/json", "Authorization", bearer);
        c.postHeaders("jwt: alternatives", "/alternatives", "{\"selection\":{\"MAT\":\"MAT-01\",\"FIS\":\"FIS-01\"},\"enrolled\":[\"MAT-01\"]}", "Content-Type", "application/json", "Authorization", bearer);
        c.postHeaders("jwt: alternatives selection not requested", "/alternatives", "{\"selection\":{\"QUI\":\"QUI-01\"}}", "Content-Type", "application/json", "Authorization", bearer);
        c.postHeaders("jwt: alternatives body differs", "/alternatives", "{\"student_id\":\"z\"}", "Content-Type", "application/json", "Authorization", bearer);
        c.postHeaders("jwt: lowercase bearer", "/combinations", "{}", "Content-Type", "application/json", "Authorization", "bearer " + ok);
        c.postHeaders("jwt: uppercase BEARER", "/combinations", "{}", "Content-Type", "application/json", "Authorization", "BEARER " + ok);
        c.postHeaders("jwt: no authorization", "/combinations", "{}", "Content-Type", "application/json");
        c.postHeaders("jwt: no authorization, request id", "/combinations", "{}", "Content-Type", "application/json", "X-Request-Id", "auth-1");
        c.postHeaders("jwt: empty authorization", "/combinations", "{}", "Content-Type", "application/json", "Authorization", "");
        c.postHeaders("jwt: Bearer only", "/combinations", "{}", "Content-Type", "application/json", "Authorization", "Bearer");
        c.postHeaders("jwt: Bearer space only", "/combinations", "{}", "Content-Type", "application/json", "Authorization", "Bearer ");
        c.postHeaders("jwt: basic", "/combinations", "{}", "Content-Type", "application/json", "Authorization", "Basic dXNlcjpwYXNz");
        c.postHeaders("jwt: token without scheme", "/combinations", "{}", "Content-Type", "application/json", "Authorization", ok);
        c.postHeaders("jwt: extra after token", "/combinations", "{}", "Content-Type", "application/json", "Authorization", bearer + " extra");
        c.postHeaders("jwt: two spaces", "/combinations", "{}", "Content-Type", "application/json", "Authorization", "Bearer  " + ok);
        c.postHeaders("jwt: wrong scheme", "/combinations", "{}", "Content-Type", "application/json", "Authorization", "Token " + ok);
        c.postHeaders("jwt: two authorization headers", "/combinations", "{}", "Content-Type", "application/json", "Authorization", bearer, "Authorization", bearer);
        c.postHeaders("jwt: tampered signature", "/combinations", "{}", "Content-Type", "application/json", "Authorization", bearer.substring(0, bearer.length() - 3) + (bearer.endsWith("AAA") ? "BBB" : "AAA"));
        c.postHeaders("jwt: expired", "/combinations", "{}", "Content-Type", "application/json", "Authorization", "Bearer " + sign(HEADER,
                "{\"iss\":\"" + ISSUER + "\",\"aud\":\"" + AUDIENCE + "\",\"sub\":\"est-1\",\"courses\":[\"MAT\"],\"iat\":" + (NOW.getEpochSecond() - 4000) + ",\"exp\":" + (NOW.getEpochSecond() - 3400) + "}"));
        c.postHeaders("jwt: wrong issuer", "/combinations", "{}", "Content-Type", "application/json", "Authorization", "Bearer " + sign(HEADER, claims("est-1", "[\"MAT\"]").replace(ISSUER, "otra")));
        c.postHeaders("jwt: wrong algorithm", "/combinations", "{}", "Content-Type", "application/json", "Authorization", "Bearer " + sign("{\"alg\":\"HS256\",\"kid\":\"k1\"}", claims("est-1", "[\"MAT\"]")));
        c.postHeaders("jwt: unknown kid", "/combinations", "{}", "Content-Type", "application/json", "Authorization", "Bearer " + sign("{\"alg\":\"EdDSA\",\"kid\":\"zz\"}", claims("est-1", "[\"MAT\"]")));
        c.postHeaders("jwt: garbage token", "/combinations", "{}", "Content-Type", "application/json", "Authorization", "Bearer a.b.c");
        c.postHeaders("jwt: claims out of limits (13 courses)", "/combinations", "{}", "Content-Type", "application/json", "Authorization",
                "Bearer " + token("est-1", "[" + "\"A\",".repeat(12) + "\"A\"]"));
        c.postHeaders("jwt: claims out of limits (bad sub)", "/combinations", "{}", "Content-Type", "application/json", "Authorization", "Bearer " + token("est 1", "[\"MAT\"]"));
        c.postHeaders("jwt: claims out of limits (long course)", "/combinations", "{}", "Content-Type", "application/json", "Authorization", "Bearer " + token("est-1", "[\"" + "C".repeat(65) + "\"]"));
        c.postHeaders("jwt: nbsp sub", "/combinations", "{}", "Content-Type", "application/json", "Authorization", "Bearer " + token("\u00a0", "[\"MAT\"]"));
        c.postHeaders("jwt: token with empty courses", "/combinations", "{}", "Content-Type", "application/json", "Authorization", "Bearer " + token("est-1", "[]"));
        c.postHeaders("jwt: token with empty courses, alternatives", "/alternatives", "{}", "Content-Type", "application/json", "Authorization", "Bearer " + token("est-1", "[]"));
        c.postHeaders("jwt: token with repeated courses", "/combinations", "{}", "Content-Type", "application/json", "Authorization", "Bearer " + token("est-1", "[\"MAT\",\"MAT\",\"FIS\"]"));
        c.postHeaders("jwt: token with credits exceeded", "/combinations", "{}", "Content-Type", "application/json", "Authorization", "Bearer " + token("est-1", "[\"MAT\",\"FIS\",\"QUI\",\"ART\",\"HIS\"]"));
        // Orden de las capas: el 401 gana a 413, 404, 405 y 415.
        String big = "x".repeat(70_000);
        c.postHeaders("jwt: 401 beats 413", "/combinations", big, "Content-Type", "application/json");
        c.postHeaders("jwt: 413 with valid token", "/combinations", big, "Content-Type", "application/json", "Authorization", bearer);
        c.postHeaders("jwt: 401 beats 413 (request id)", "/combinations", big, "Content-Type", "application/json", "X-Request-Id", "big-401");
        c.postHeaders("jwt: 413 request id", "/combinations", big, "Content-Type", "application/json", "Authorization", bearer, "X-Request-Id", "big-413");
        c.postHeaders("jwt: 401 beats 404", "/nope", "{}", "Content-Type", "application/json");
        c.postHeaders("jwt: 404 with valid token", "/nope", "{}", "Content-Type", "application/json", "Authorization", bearer);
        c.postHeaders("jwt: 401 beats 415", "/combinations", "{}", "Content-Type", "text/plain");
        c.postHeaders("jwt: 415 with valid token", "/combinations", "{}", "Content-Type", "text/plain", "Authorization", bearer);
        c.get("jwt: GET /combinations no token", "GET", "/combinations");
        c.get("jwt: GET /combinations with token", "GET", "/combinations", "Authorization", bearer);
        c.get("jwt: GET /health/x no token", "GET", "/health/x");
        c.get("jwt: GET /health", "GET", "/health");
        c.get("jwt: POST /health no token", "POST", "/health");
        c.get("jwt: GET /ready", "GET", "/ready");
        c.get("jwt: GET /ready request id", "GET", "/ready", "X-Request-Id", "ready-1");
        c.get("jwt: GET /app", "GET", "/app");
        c.get("jwt: GET /app/", "GET", "/app/");
        c.get("jwt: HEAD /app/", "HEAD", "/app/");
        c.get("jwt: GET /app/index.html", "GET", "/app/index.html");
        c.get("jwt: GET /app/js/main.js", "GET", "/app/js/main.js");
        c.get("jwt: GET /app/nope", "GET", "/app/nope");
        c.get("jwt: POST /app/index.html", "POST", "/app/index.html");
        c.get("jwt: PUT /app/", "PUT", "/app/");
        c.get("jwt: GET /app/../combinations", "GET", "/app/../combinations");
        c.get("jwt: GET /app/%2e%2e/combinations", "GET", "/app/%2e%2e/combinations");
        c.get("jwt: GET /app//x", "GET", "/app//x");
        c.get("jwt: GET /app;x=1/y", "GET", "/app;x=1/y");
        c.get("jwt: GET /app/a%20b", "GET", "/app/a%20b");
        c.get("jwt: GET /appx", "GET", "/appx");
        c.get("jwt: GET /apps/x", "GET", "/apps/x");
        c.get("jwt: GET /application", "GET", "/application");
        c.get("jwt: GET /app?x=1", "GET", "/app?x=1");
        c.get("jwt: GET /nope", "GET", "/nope");
        c.get("jwt: GET /", "GET", "/");
        c.get("jwt: OPTIONS /combinations", "OPTIONS", "/combinations");
        return c;
    }

    // ---- Ejecución ------------------------------------------------------------------------------

    private static ConfigurableApplicationContext start(Profile profile) throws Exception {
        HttpOnlyConfig.snapshot = profile.catalog() == null ? null : profile.catalog().snapshot();
        HttpOnlyConfig.verifier = profile.jwt()
                ? new JwtVerifier(Map.of(KID, publicKey()),
                        new JwtVerifier.Settings(ISSUER, AUDIENCE, Duration.ofSeconds(30), Duration.ofSeconds(900)),
                        Clock.fixed(NOW, ZoneOffset.UTC))
                : null;
        return new SpringApplicationBuilder(HttpOnlyConfig.class)
                .web(WebApplicationType.SERVLET)
                // Como argumentos: tienen más precedencia que application.properties (que fija los
                // valores por defecto de producción con ${SCHED_*:valor}).
                .run(
                        "--server.port=0",
                        "--management.server.port=-1",
                        "--sched.auth=" + (profile.jwt() ? "jwt" : "none"),
                        "--sched.max-combinations=" + profile.maxCombinations(),
                        "--sched.max-alternatives=" + profile.maxAlternatives(),
                        "--sched.limits.max-courses=" + profile.limits().maxCourses(),
                        "--sched.limits.max-avoid=" + profile.limits().maxAvoid(),
                        "--sched.limits.max-id-length=" + profile.limits().maxIdLength(),
                        "--sched.limits.max-body-bytes=" + profile.limits().maxBodyBytes(),
                        "--spring.main.banner-mode=off");
    }

    private static final java.util.Set<String> IGNORED_HEADERS = java.util.Set.of(
            "date", "connection", "keep-alive", "transfer-encoding", "content-length", "last-modified",
            "etag", "accept-ranges", "vary");

    private static ObjectNode execute(HttpClient client, int port, Req req) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + rawPath(req.path())))
                .timeout(Duration.ofSeconds(30));
        for (String[] header : req.headers()) {
            builder.header(header[0], header[1]);
        }
        HttpRequest.BodyPublisher publisher = req.chunked()
                ? HttpRequest.BodyPublishers.fromPublisher(HttpRequest.BodyPublishers.ofByteArray(req.body()))
                : HttpRequest.BodyPublishers.ofByteArray(req.body());
        boolean hasBody = !req.method().equals("GET") && !req.method().equals("HEAD") && !req.method().equals("OPTIONS")
                && !req.method().equals("TRACE") && !req.method().equals("DELETE");
        if (req.method().equals("DELETE")) {
            builder.method("DELETE", HttpRequest.BodyPublishers.noBody());
        } else {
            builder.method(req.method(), hasBody ? publisher : HttpRequest.BodyPublishers.noBody());
        }
        HttpResponse<byte[]> response = client.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());

        ObjectNode out = JSON.createObjectNode();
        out.put("name", req.name());
        out.put("method", req.method());
        out.put("path", req.path());
        ArrayNode headers = out.putArray("headers");
        for (String[] header : req.headers()) {
            headers.addArray().add(header[0]).add(header[1]);
        }
        if (hasBody || req.body().length > 0) {
            boolean utf8 = isUtf8(req.body());
            if (utf8) {
                out.put("body", new String(req.body(), StandardCharsets.UTF_8));
            } else {
                out.put("body_b64", Base64.getEncoder().encodeToString(req.body()));
            }
        }
        if (req.chunked()) {
            out.put("chunked", true);
        }
        ObjectNode expect = out.putObject("expect");
        expect.put("status", response.statusCode());
        ObjectNode responseHeaders = expect.putObject("headers");
        List<String> names = new ArrayList<>(response.headers().map().keySet());
        java.util.Collections.sort(names);
        for (String name : names) {
            if (IGNORED_HEADERS.contains(name.toLowerCase())) {
                continue;
            }
            String value = String.join(", ", response.headers().map().get(name));
            if (name.equalsIgnoreCase("x-request-id") && UUID.matcher(value).matches()) {
                value = "<uuid>";
            }
            if (name.equalsIgnoreCase("location")) {
                // URL absoluta con el puerto efímero de la prueba: se normaliza el host.
                value = value.replaceFirst("^http://127\\.0\\.0\\.1:\\d+/", "http://<host>/");
            }
            responseHeaders.put(name.toLowerCase(), value);
        }
        byte[] body = response.body();
        if (req.path().startsWith("/app") && response.statusCode() == 200 && body.length > 0) {
            expect.put("body_sha256", HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body)));
            expect.put("body_len", body.length);
        } else if (isUtf8(body)) {
            // El "timestamp" de los errores de Spring cambia en cada corrida: se normaliza para que el archivo sea
            // determinista.
            expect.put("body", new String(body, StandardCharsets.UTF_8)
                    .replaceFirst("^\\{\"timestamp\":\"[^\"]*\"", "{\"timestamp\":\"<ts>\""));
        } else {
            expect.put("body_b64", Base64.getEncoder().encodeToString(body));
        }
        return out;
    }

    /** El camino tal como se envía (sin que el cliente lo normalice): solo se escapa lo que URI.create no admite. */
    private static String rawPath(String path) {
        return path.replace("\\", "%5C");
    }

    private static boolean isUtf8(byte[] bytes) {
        try {
            StandardCharsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes));
            return true;
        } catch (java.nio.charset.CharacterCodingException e) {
            return false;
        }
    }

    @Test
    void exportRequestCases() throws Exception {
        Path repo = Path.of("..").toAbsolutePath().normalize();
        Path benchDir = repo.resolve("bench");
        Catalog scenario = scenario(benchDir.resolve("scenario.json"));
        Catalog mini10 = mini("mini", 10);
        Catalog mini0 = mini("mini-nocap", 0);
        RequestLimits defaults = RequestLimits.DEFAULTS;
        RequestLimits tight = new RequestLimits(3, 2, 8, 256);

        Cases miniCases = miniCases();
        Cases altCases = alternativesCases();
        miniCases.list.addAll(altCases.list);

        Cases nocap = new Cases();
        nocap.post("nocap: max_credits 0", "/combinations", student("s1", "MAT", "FIS", "QUI", "ART", "HIS"));
        nocap.post("nocap: alternatives", "/alternatives", "{\"student_id\":\"s1\",\"eligible_courses\":[\"MAT\",\"FIS\",\"QUI\",\"ART\",\"HIS\"],\"selection\":{\"MAT\":\"MAT-02\"}}");

        List<Profile> profiles = List.of(
                new Profile("scenario", scenario, false, 5, 5, defaults),
                new Profile("scenario-200", scenario, false, 200, 5, defaults),
                new Profile("mini", mini10, false, 10, 5, defaults),
                new Profile("mini-nocap", mini0, false, 10, 5, defaults),
                new Profile("tight", mini10, false, 10, 5, tight),
                new Profile("jwt", mini10, true, 10, 5, defaults),
                new Profile("unloaded", null, false, 10, 5, defaults));
        Map<String, Cases> casesByProfile = new LinkedHashMap<>();
        casesByProfile.put("scenario", scenarioCases(benchDir));
        casesByProfile.put("scenario-200", wideCases());
        casesByProfile.put("mini", miniCases);
        casesByProfile.put("mini-nocap", nocap);
        casesByProfile.put("tight", tightCases());
        casesByProfile.put("jwt", jwtCases());
        Cases unloaded = new Cases();
        unloaded.post("unloaded: combinations", "/combinations", student("s1", "MAT"));
        unloaded.post("unloaded: alternatives", "/alternatives", "{\"student_id\":\"s1\",\"eligible_courses\":[\"MAT\"],\"selection\":{\"MAT\":\"MAT-01\"}}");
        unloaded.post("unloaded: invalid request wins over 503", "/combinations", "{}");
        unloaded.post("unloaded: invalid selection wins over 503", "/alternatives", "{\"student_id\":\"s1\",\"eligible_courses\":[\"MAT\"],\"selection\":{\"FIS\":\"x\"}}");
        unloaded.get("unloaded: ready", "GET", "/ready");
        unloaded.get("unloaded: health", "GET", "/health");
        casesByProfile.put("unloaded", unloaded);

        ObjectNode root = JSON.createObjectNode();
        root.put("_comment", "Generado por sched.server.ExportRequestCasesTest (repositorio scheduling-engine-java); "
                + "no editar a mano. Cada caso es una solicitud HTTP real al motor Java (Spring Boot + Tomcat, sin NATS) "
                + "y la respuesta exacta que dio. X-Request-Id generado se normaliza a <uuid>. "
                + "La llave de prueba es publica (semilla fija) y no es un secreto.");
        root.put("jwt_now_epoch", NOW.getEpochSecond());
        ObjectNode jwt = root.putObject("jwt");
        jwt.put("issuer", ISSUER);
        jwt.put("audience", AUDIENCE);
        jwt.put("kid", KID);
        jwt.put("clock_skew_s", 30);
        jwt.put("max_ttl_s", 900);
        jwt.put("public_key_pem", publicKeyPem());
        ObjectNode profilesNode = root.putObject("profiles");
        for (Profile p : profiles) {
            ObjectNode node = profilesNode.putObject(p.name());
            node.put("auth", p.jwt() ? "jwt" : "none");
            node.put("catalog", p.catalog() == null ? "none" : p.catalog().name());
            node.put("max_combinations", p.maxCombinations());
            node.put("max_alternatives", p.maxAlternatives());
            node.put("max_courses", p.limits().maxCourses());
            node.put("max_avoid", p.limits().maxAvoid());
            node.put("max_id_length", p.limits().maxIdLength());
            node.put("max_body_bytes", p.limits().maxBodyBytes());
        }
        ObjectNode catalogs = root.putObject("catalogs");
        for (Catalog catalog : List.of(scenario, mini10, mini0)) {
            catalogs.set(catalog.name(), catalog.spec());
        }

        ArrayNode all = root.putArray("cases");
        HttpClient client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
                .followRedirects(HttpClient.Redirect.NEVER).connectTimeout(Duration.ofSeconds(10)).build();
        int total = 0;
        Map<Integer, Integer> byStatus = new java.util.TreeMap<>();
        for (Profile profile : profiles) {
            try (ConfigurableApplicationContext context = start(profile)) {
                int port = Integer.parseInt(context.getEnvironment().getProperty("local.server.port"));
                for (Req req : casesByProfile.get(profile.name()).list) {
                    ObjectNode result = execute(client, port, req);
                    result.put("profile", profile.name());
                    all.add(result);
                    total++;
                    byStatus.merge(result.get("expect").get("status").asInt(), 1, Integer::sum);
                }
            }
        }
        Path target = Path.of("target", "request-cases.json");
        Files.createDirectories(target.getParent());
        String json = JSON.writerWithDefaultPrettyPrinter().writeValueAsString(root);
        Files.writeString(target, json + "\n", StandardCharsets.UTF_8);
        String export = System.getProperty("sched.export.request-cases");
        if (export != null && !export.isBlank()) {
            Files.writeString(Path.of(export), json + "\n", StandardCharsets.UTF_8);
        }
        System.out.println("request-cases: " + total + " casos, por código: " + byStatus);
        assertTrue(total > 500, "pocos casos: " + total);
        for (int status : new int[] {200, 400, 401, 404, 405, 413, 415, 422}) {
            assertTrue(byStatus.containsKey(status), "ningún caso con código " + status + ": " + byStatus);
        }
    }
}
