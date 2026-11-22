package sched.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.ThrowableProxyUtil;
import ch.qos.logback.core.read.ListAppender;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import sched.adapters.http.AlternativesController;
import sched.adapters.http.BodySizeLimitFilter;
import sched.adapters.http.CombinationsController;
import sched.adapters.http.HttpExceptionHandler;
import sched.adapters.http.JwtAuthFilter;
import sched.adapters.http.RequestIdFilter;
import sched.adapters.http.RequestLimits;
import sched.adapters.http.auth.JwtVerifier;
import sched.application.ApplyLogEntry;
import sched.application.CatalogLogEntry;
import sched.application.CatalogReplica;
import sched.application.GenerateCombinations;
import sched.application.SuggestAlternatives;
import sched.domain.CourseId;
import sched.domain.Section;
import sched.domain.SectionId;
import sched.domain.SectionSeats;
import sched.domain.TimeSlot;
import sched.domain.Weekday;
import sched.solver.ForwardCheckingSolver;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.json.JsonMapper;

/**
 * Política de confidencialidad de los logs: el motor no conserva datos personales, así que ni el
 * token, ni el cuerpo de la solicitud, ni el id del estudiante deben aparecer en ninguna línea,
 * ni siquiera con el nivel DEBUG. Y el id de correlación sí debe acompañar a las líneas de la solicitud.
 */
class LogConfidentialityTest {

    private static final String STUDENT = "est-secreto-4711";
    private static final String SECRET_BODY_MARKER = "MARCA-CUERPO-SECRETA";

    private final TestJwt jwt = new TestJwt();
    private final Logger root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();
    private Level previousLevel;
    private MockMvc mvc;

    @BeforeEach
    void setUp() throws Exception {
        previousLevel = root.getLevel();
        root.setLevel(Level.DEBUG);
        // Igual que application.properties: Spring MVC registra en DEBUG el cuerpo ya leído y la respuesta.
        ((Logger) LoggerFactory.getLogger("org.springframework.web")).setLevel(Level.INFO);
        logs.start();
        root.addAppender(logs);

        CatalogReplica replica = new CatalogReplica();
        new ApplyLogEntry(replica).execute(new CatalogLogEntry.Snapshot(1, List.of(
                new SectionSeats(new Section(new SectionId("MAT-01"), new CourseId("MAT"),
                        List.of(TimeSlot.of(Weekday.MONDAY, 420, 480).orElseThrow()), 30), 5))));
        var verifier = new JwtVerifier(Map.of(TestJwt.KID, jwt.publicKey()),
                new JwtVerifier.Settings(TestJwt.ISSUER, TestJwt.AUDIENCE, Duration.ofSeconds(30), Duration.ofMinutes(15)));
        var limits = new RequestLimits(12, 42, 64, 512);
        var solver = new ForwardCheckingSolver();
        JsonMapper mapper = JsonMapper.builder().propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE).build();
        mvc = MockMvcBuilders.standaloneSetup(
                        new CombinationsController(new GenerateCombinations(solver, replica, 10), limits),
                        new AlternativesController(new SuggestAlternatives(solver, replica, 5), limits))
                .setControllerAdvice(new HttpExceptionHandler())
                .setMessageConverters(new JacksonJsonHttpMessageConverter(mapper))
                .addFilters(new RequestIdFilter(), new HttpMetricsFilter(new SimpleMeterRegistry()),
                        new JwtAuthFilter(verifier, limits), new BodySizeLimitFilter(limits.maxBodyBytes()))
                .build();
    }

    @AfterEach
    void tearDown() {
        root.detachAppender(logs);
        root.setLevel(previousLevel);
        ((Logger) LoggerFactory.getLogger("org.springframework.web")).setLevel(null);
    }

    private int send(String path, String authorization, String body, String requestId) throws Exception {
        var request = post(path).contentType(MediaType.APPLICATION_JSON).content(body);
        if (authorization != null) {
            request.header("Authorization", authorization);
        }
        if (requestId != null) {
            request.header("X-Request-Id", requestId);
        }
        return mvc.perform(request).andReturn().getResponse().getStatus();
    }

    /** Todo lo que un log podría llevar de una línea: mensaje, MDC, argumentos y la traza de la excepción. */
    private List<String> everythingLogged() {
        List<String> lines = new ArrayList<>();
        for (ILoggingEvent event : logs.list) {
            lines.add(event.getFormattedMessage() + " " + event.getMDCPropertyMap()
                    + " " + (event.getThrowableProxy() == null ? "" : ThrowableProxyUtil.asString(event.getThrowableProxy())));
        }
        return lines;
    }

    private void assertNeverLogged(String... secrets) {
        for (String line : everythingLogged()) {
            for (String secret : secrets) {
                assertFalse(line.contains(secret), "el log contiene '" + secret + "': " + line);
            }
        }
    }

    @Test
    void tokensBodiesAndStudentIdsNeverReachTheLogs() throws Exception {
        String valid = jwt.token(STUDENT, List.of("MAT"));
        String stranger = new TestJwt().token(STUDENT, List.of("MAT"));
        String[] parts = valid.split("\\.");
        String body = "{\"avoid\":[{\"day\":\"monday\",\"start_minute\":0,\"end_minute\":60}],\"nota\":\""
                + SECRET_BODY_MARKER + "\"}";

        send("/combinations", "Bearer " + valid, body, "req-1");
        send("/alternatives", "Bearer " + valid, "{\"selection\":{\"MAT\":\"MAT-01\"}}", "req-2");
        // Rechazos: token de otra institución, mal formado, ausente, cuerpo ilegible, cuerpo enorme, contexto alterado.
        assertEquals(401, send("/combinations", "Bearer " + stranger, body, "req-3"));
        assertEquals(401, send("/combinations", "Bearer " + valid + "x", body, "req-4"));
        assertEquals(401, send("/combinations", null, body, "req-5"));
        assertEquals(400, send("/combinations", "Bearer " + valid, "{\"nota\": \"" + SECRET_BODY_MARKER, "req-6"));
        assertEquals(413, send("/combinations", "Bearer " + valid,
                "{\"nota\":\"" + SECRET_BODY_MARKER + "x".repeat(1000) + "\"}", "req-7"));
        assertEquals(400, send("/combinations", "Bearer " + valid, "{\"student_id\":\"" + SECRET_BODY_MARKER + "\"}", "req-8"));

        assertFalse(logs.list.isEmpty(), "la prueba debe haber capturado logs (los rechazos se registran)");
        assertNeverLogged(valid, stranger, parts[0], parts[1], parts[2], "Bearer", STUDENT, SECRET_BODY_MARKER);
    }

    @Test
    void theCorrelationIdAccompaniesTheLogLinesOfTheRequest() throws Exception {
        assertEquals(401, send("/combinations", "Bearer basura", "{}", "correlacion-777"));

        var rejection = logs.list.stream()
                .filter(e -> e.getFormattedMessage().contains("rechazado")).findFirst().orElseThrow();
        assertEquals("correlacion-777", rejection.getMDCPropertyMap().get("request_id"));
        assertTrue(logs.list.stream().noneMatch(e -> e.getMDCPropertyMap().containsKey("request_id")
                && !e.getMDCPropertyMap().get("request_id").equals("correlacion-777")));
    }
}
