package sched.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.ThrowableProxyUtil;
import ch.qos.logback.core.read.ListAppender;
import io.grpc.ChannelCredentials;
import io.grpc.Grpc;
import io.grpc.InsecureChannelCredentials;
import io.grpc.ManagedChannel;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.TlsChannelCredentials;
import io.grpc.stub.StreamObserver;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import sched.domain.CourseId;
import sched.domain.SectionId;
import sched.domain.TimeSlot;
import sched.domain.Weekday;
import sched.proto.Convert;
import sched.proto.institution.v1.CatalogChunk;
import sched.proto.institution.v1.CatalogEvent;
import sched.proto.institution.v1.CatalogEventsServiceGrpc;
import sched.proto.institution.v1.CatalogServiceGrpc;
import sched.proto.institution.v1.LoadCatalogResponse;
import sched.proto.institution.v1.SeatsChanged;
import sched.proto.institution.v1.SyncStatus;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * El motor completo, arrancado como en producción (Spring Boot, puerto HTTP real) contra un NATS
 * JetStream real (Testcontainers; se salta sin Docker), con mTLS en la ingesta y token firmado
 * (Ed25519) en las consultas: carga por gRPC, consultas HTTP con JSON real, rechazos, métricas en el
 * puerto de administración, id de correlación y confidencialidad de los logs.
 *
 * <p>Las pruebas comparten el contexto y van en orden: la primera carga el catálogo.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(classes = SchedServer.class, webEnvironment = RANDOM_PORT, properties = {
        "management.server.port=0",
        "sched.grpc.port=0",
        "sched.max-combinations=50",
        "sched.limits.max-body-bytes=2048",
        // Nivel DEBUG en todo: la prueba de logs comprueba que aun así no se filtra nada.
        "logging.level.root=DEBUG",
        "sched.warmup=off",
        "logging.level.io.netty=INFO",
        "logging.level.io.grpc=INFO",
        "logging.level.com.github.dockerjava=INFO",
        "logging.level.org.testcontainers=INFO",
        "logging.level.org.apache=INFO",
})
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class EngineEndToEndTest {

    private static final String STUDENT = "est-e2e-secreto-9001";
    private static final String BODY_MARKER = "MARCA-CUERPO-E2E";
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final TestJwt JWT = new TestJwt();
    private static final TestPki.Identity CA = pki(() -> TestPki.newCa("sched-e2e-ca"));
    private static Path pkiDir;
    private static TestPki.Pem clientPem;
    private static Path caCert;

    @Container
    static final GenericContainer<?> NATS = new GenericContainer<>("nats:2")
            .withCommand("-js")
            .withExposedPorts(4222)
            .waitingFor(Wait.forLogMessage(".*Server is ready.*", 1));

    private static <T> T pki(java.util.concurrent.Callable<T> action) {
        try {
            return action.call();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) throws Exception {
        pkiDir = Files.createTempDirectory("sched-e2e");
        caCert = CA.write(pkiDir, "ca").cert();
        var serverPem = TestPki.newServer(CA, "ingest").write(pkiDir, "server");
        clientPem = TestPki.newClient(CA, "institucion").write(pkiDir, "client");
        Path keys = Files.createDirectory(pkiDir.resolve("keys"));
        JWT.writePublicKey(keys);

        String unique = UUID.randomUUID().toString().substring(0, 8);
        registry.add("sched.nats.url", () -> "nats://" + NATS.getHost() + ":" + NATS.getMappedPort(4222));
        registry.add("sched.nats.stream", () -> "E2E_" + unique);
        registry.add("sched.nats.subject-prefix", () -> "e2e" + unique);
        registry.add("sched.grpc.tls", () -> "required");
        registry.add("sched.grpc.tls-cert", () -> serverPem.cert().toString());
        registry.add("sched.grpc.tls-key", () -> serverPem.key().toString());
        registry.add("sched.grpc.tls-client-ca", () -> caCert.toString());
        registry.add("sched.auth", () -> "jwt");
        registry.add("sched.jwt.public-keys", keys::toString);
        registry.add("sched.jwt.issuer", () -> TestJwt.ISSUER);
        registry.add("sched.jwt.audience", () -> TestJwt.AUDIENCE);
    }

    @Value("${local.server.port}")
    private int httpPort;

    @Value("${local.management.port}")
    private int adminPort;

    @Autowired
    private IngestGrpcServer grpc;

    private final HttpClient http = HttpClient.newHttpClient();
    private static long seq = 0;
    private static boolean loaded = false;
    private static final ListAppender<ILoggingEvent> LOGS = new ListAppender<>();
    private static final List<String> SECRETS = new ArrayList<>();

    @BeforeEach
    void captureLogs() {
        // Spring Boot reinicia logback al arrancar: el appender se engancha ya con el contexto listo.
        Logger root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        if (!root.isAttached(LOGS)) {
            LOGS.start();
            root.addAppender(LOGS);
        }
    }

    @AfterAll
    static void detachLogs() {
        ((Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME)).detachAppender(LOGS);
    }

    // Ayudas: gRPC de la institución

    private ChannelCredentials clientCredentials() throws Exception {
        return TlsChannelCredentials.newBuilder().trustManager(caCert.toFile())
                .keyManager(clientPem.cert().toFile(), clientPem.key().toFile()).build();
    }

    private ManagedChannel channel(ChannelCredentials credentials) {
        await("el gRPC de ingesta abre su puerto", () -> grpc.boundPort() > 0);
        return Grpc.newChannelBuilder("localhost:" + grpc.boundPort(), credentials).build();
    }

    private static sched.domain.Section section(String id, String course, Weekday day, int start) {
        return new sched.domain.Section(new SectionId(id), new CourseId(course),
                List.of(TimeSlot.of(day, start, start + 120).orElseThrow()), 30);
    }

    /** Carga completa con créditos y reglas (tope de 12 créditos) con las credenciales dadas. */
    private LoadCatalogResponse loadCatalog(ChannelCredentials credentials) throws Exception {
        ManagedChannel channel = channel(credentials);
        try {
            CompletableFuture<LoadCatalogResponse> result = new CompletableFuture<>();
            StreamObserver<CatalogChunk> chunks = CatalogServiceGrpc.newStub(channel)
                    .withDeadlineAfter(20, TimeUnit.SECONDS).loadCatalog(new StreamObserver<>() {
                        @Override
                        public void onNext(LoadCatalogResponse response) {
                            result.complete(response);
                        }

                        @Override
                        public void onError(Throwable error) {
                            result.completeExceptionally(error);
                        }

                        @Override
                        public void onCompleted() {
                        }
                    });
            var chunk = CatalogChunk.newBuilder().setSeq(seq + 1)
                    // MAT-01 y FIS-01 se cruzan (lunes 7:00).
                    .addSections(Convert.sectionToPb(section("MAT-01", "MAT", Weekday.MONDAY, 420), 5))
                    .addSections(Convert.sectionToPb(section("MAT-02", "MAT", Weekday.TUESDAY, 420), 5))
                    .addSections(Convert.sectionToPb(section("FIS-01", "FIS", Weekday.MONDAY, 420), 5))
                    .addSections(Convert.sectionToPb(section("FIS-02", "FIS", Weekday.WEDNESDAY, 420), 5))
                    .addSections(Convert.sectionToPb(section("QUI-01", "QUI", Weekday.THURSDAY, 420), 5))
                    .addSections(Convert.sectionToPb(section("HIS-01", "HIS", Weekday.FRIDAY, 420), 5))
                    .addCourses(Convert.courseToPb(new sched.domain.Course(new CourseId("MAT"), 4)))
                    .addCourses(Convert.courseToPb(new sched.domain.Course(new CourseId("FIS"), 4)))
                    .addCourses(Convert.courseToPb(new sched.domain.Course(new CourseId("QUI"), 3)))
                    .addCourses(Convert.courseToPb(new sched.domain.Course(new CourseId("HIS"), 5)))
                    .setRules(Convert.rulesToPb(new sched.domain.EnrollmentRules(12)))
                    .build();
            chunks.onNext(chunk);
            chunks.onCompleted();
            LoadCatalogResponse response = result.get(30, TimeUnit.SECONDS);
            seq = response.getSeq();
            return response;
        } finally {
            channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    private LoadCatalogResponse loadCatalog() throws Exception {
        return loadCatalog(clientCredentials());
    }

    private void ensureCatalog() throws Exception {
        if (seq == 0) {
            loadCatalog();
        }
        await("la instancia está lista", () -> get(httpPort, "/ready").statusCode() == 200);
    }

    /** Un flujo de cambios abierto contra la ingesta: las respuestas llegan a una cola. */
    private final class EventStream implements AutoCloseable {
        private final ManagedChannel channel = channel(clientCredentialsUnchecked());
        private final BlockingQueue<SyncStatus> responses = new LinkedBlockingQueue<>();
        private final StreamObserver<CatalogEvent> requests;

        EventStream() {
            requests = CatalogEventsServiceGrpc.newStub(channel).syncChanges(new StreamObserver<>() {
                @Override
                public void onNext(SyncStatus status) {
                    responses.add(status);
                }

                @Override
                public void onError(Throwable error) {
                }

                @Override
                public void onCompleted() {
                }
            });
        }

        SyncStatus send(long eventSeq, String section, int seats) throws Exception {
            requests.onNext(CatalogEvent.newBuilder().setSeq(eventSeq).setSeatsChanged(
                    SeatsChanged.newBuilder().setSectionId(section).setAvailableSeats(seats)).build());
            SyncStatus status = responses.poll(20, TimeUnit.SECONDS);
            assertNotNull(status, "la ingesta no respondió al cambio " + eventSeq);
            return status;
        }

        @Override
        public void close() throws Exception {
            requests.onCompleted();
            channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    private ChannelCredentials clientCredentialsUnchecked() {
        try {
            return clientCredentials();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    // Ayudas: HTTP

    private static void await(String what, BooleanSupplier condition) {
        long deadline = System.nanoTime() + Duration.ofSeconds(40).toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("no ocurrió a tiempo: " + what);
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError(e);
            }
        }
    }

    private HttpResponse<String> send(int port, String method, String path, String bearer, String body,
            String requestId) {
        var builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).timeout(Duration.ofSeconds(20));
        if (bearer != null) {
            builder.header("Authorization", "Bearer " + bearer);
        }
        if (requestId != null) {
            builder.header("X-Request-Id", requestId);
        }
        if (body != null) {
            builder.header("Content-Type", "application/json").method(method,
                    HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        } else {
            builder.method(method, HttpRequest.BodyPublishers.noBody());
        }
        try {
            return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private HttpResponse<String> get(int port, String path) {
        return send(port, "GET", path, null, null, null);
    }

    private HttpResponse<String> post(String path, String token, String body) {
        return send(httpPort, "POST", path, token, body, null);
    }

    private String token(String... courses) {
        String token = JWT.token(STUDENT, List.of(courses));
        SECRETS.add(token);
        return token;
    }

    private static JsonNode json(HttpResponse<String> response) {
        return JSON.readTree(response.body());
    }

    private static List<String> sectionIds(JsonNode schedule) {
        List<String> ids = new ArrayList<>();
        schedule.get("sections").forEach(section -> ids.add(section.get("section_id").asString()));
        return ids;
    }

    /** Valor de una muestra del texto de Prometheus: la primera línea que empieza por el nombre y trae las etiquetas. */
    private static double sample(String exposition, String name, String... labels) {
        for (String line : exposition.split("\n")) {
            if (line.startsWith(name + "{") || line.startsWith(name + " ")) {
                boolean all = true;
                for (String label : labels) {
                    all &= line.contains(label);
                }
                if (all) {
                    return Double.parseDouble(line.substring(line.lastIndexOf(' ') + 1));
                }
            }
        }
        throw new AssertionError("no hay una muestra " + name + " " + List.of(labels) + " en:\n" + exposition);
    }

    // Pruebas

    @Test
    @Order(1)
    void theIngestRequiresMutualTlsAndALegitimateClientLoadsTheCatalog() throws Exception {
        // Sin certificado de cliente, con el de otra CA o en texto plano, la conexión falla.
        var otherCa = TestPki.newCa("otra-ca");
        var strangerPem = TestPki.newClient(otherCa, "intruso").write(pkiDir, "intruso");
        for (ChannelCredentials credentials : new ChannelCredentials[] {
                TlsChannelCredentials.newBuilder().trustManager(caCert.toFile()).build(),
                TlsChannelCredentials.newBuilder().trustManager(caCert.toFile())
                        .keyManager(strangerPem.cert().toFile(), strangerPem.key().toFile()).build(),
                InsecureChannelCredentials.create()}) {
            ExecutionException error = assertThrows(ExecutionException.class, () -> loadCatalog(credentials));
            var status = assertInstanceOf(StatusRuntimeException.class, error.getCause());
            assertEquals(Status.Code.UNAVAILABLE, status.getStatus().getCode(), status.getMessage());
        }

        LoadCatalogResponse response = loadCatalog();

        assertEquals(1, response.getSeq());
        assertEquals(6, response.getSectionsLoaded());
        await("la instancia está lista", () -> get(httpPort, "/ready").statusCode() == 200);
    }

    @Test
    @Order(2)
    void combinationsAnswersRealJsonInSnakeCaseForAnAuthenticatedStudent() throws Exception {
        ensureCatalog();

        var response = send(httpPort, "POST", "/combinations", token("MAT", "FIS"), "{}", "e2e-combinaciones-1");

        assertEquals(200, response.statusCode(), response.body());
        assertEquals("e2e-combinaciones-1", response.headers().firstValue("X-Request-Id").orElseThrow());
        assertTrue(response.headers().firstValue("Content-Type").orElseThrow().startsWith("application/json"));
        JsonNode body = json(response);
        assertEquals(seq, body.get("catalog_seq").asLong());
        assertFalse(body.get("stale").asBoolean());
        assertTrue(body.get("complete").asBoolean());
        assertEquals(8, body.get("total_credits").asInt());
        assertEquals(12, body.get("max_credits").asInt());
        // MAT-01 y FIS-01 se cruzan: de las cuatro combinaciones quedan tres.
        List<List<String>> schedules = new ArrayList<>();
        body.get("schedules").forEach(schedule -> schedules.add(sectionIds(schedule)));
        assertEquals(3, schedules.size(), schedules.toString());
        assertTrue(schedules.stream().noneMatch(ids -> ids.contains("MAT-01") && ids.contains("FIS-01")));
        var firstSlot = body.get("schedules").get(0).get("sections").get(0).get("slots").get(0);
        assertTrue(firstSlot.has("start_minute") && firstSlot.has("end_minute") && firstSlot.has("day"), firstSlot.toString());
    }

    @Test
    @Order(3)
    void combinationsHonoursTheStudentPreferencesFromTheBody() throws Exception {
        ensureCatalog();
        String avoidMonday = "{\"avoid\":[{\"day\":\"monday\",\"start_minute\":0,\"end_minute\":1440}]}";

        var response = post("/combinations", token("MAT", "FIS"), avoidMonday);

        assertEquals(200, response.statusCode(), response.body());
        // Sin lunes: la mejor combinación es (MAT-02, FIS-02), con penalización 0.
        var best = json(response).get("schedules").get(0);
        assertEquals(0, best.get("penalty").asLong());
        assertEquals(List.of("FIS-02", "MAT-02"), sectionIds(best).stream().sorted().toList());
    }

    @Test
    @Order(4)
    void alternativesProposesChangesForAClashingSelection() throws Exception {
        ensureCatalog();
        String selection = "{\"selection\":{\"MAT\":\"MAT-01\",\"FIS\":\"FIS-01\"}}";

        var response = post("/alternatives", token("MAT", "FIS"), selection);

        assertEquals(200, response.statusCode(), response.body());
        JsonNode body = json(response);
        assertFalse(body.get("selection_valid").asBoolean());
        assertEquals(1, body.get("conflicts").get("clashes").size());
        assertTrue(body.get("complete").asBoolean());
        var first = body.get("alternatives").get(0);
        assertEquals(1, first.get("distance").asInt());
        assertTrue(first.get("changes").size() >= 1);
    }

    @Test
    @Order(5)
    void rejectionsAre401For400For413And422() throws Exception {
        ensureCatalog();
        String valid = token("MAT", "FIS");
        String tokenForOverflow = token("MAT", "FIS", "QUI", "HIS");

        var noToken = send(httpPort, "POST", "/combinations", null, "{}", "e2e-sin-token");
        assertEquals(401, noToken.statusCode());
        assertEquals("{\"error\":\"unauthorized\"}", noToken.body());
        assertEquals("Bearer", noToken.headers().firstValue("WWW-Authenticate").orElseThrow());
        assertEquals("e2e-sin-token", noToken.headers().firstValue("X-Request-Id").orElseThrow());
        String forged = new TestJwt().token(STUDENT, List.of("MAT"));
        SECRETS.add(forged);
        assertEquals(401, post("/combinations", forged, "{}").statusCode());
        assertEquals(401, post("/alternatives", "no.es.token", "{}").statusCode());

        String garbled = "{\"avoid\": [" + BODY_MARKER;
        SECRETS.add(BODY_MARKER);
        var malformed = post("/combinations", valid, garbled);
        assertEquals(400, malformed.statusCode());
        assertEquals("invalid_request", json(malformed).get("error").asString());

        String huge = "{\"nota\":\"" + "x".repeat(4096) + "\"}";
        var tooLarge = post("/combinations", valid, huge);
        assertEquals(413, tooLarge.statusCode());
        assertEquals("payload_too_large", json(tooLarge).get("error").asString());

        // 16 créditos pedidos con un tope de 12.
        var overLimit = post("/combinations", tokenForOverflow, "{}");
        assertEquals(422, overLimit.statusCode());
        JsonNode error = json(overLimit);
        assertEquals("credit_limit_exceeded", error.get("error").asString());
        assertEquals(16, error.get("total_credits").asInt());
        assertEquals(12, error.get("max_credits").asInt());

        // El cuerpo no puede contradecir al token.
        assertEquals(400, post("/combinations", valid, "{\"student_id\":\"otro\"}").statusCode());
    }

    @Test
    @Order(6)
    void healthAndReadyStayOpenWithoutTokenOnTheStudentPort() throws Exception {
        ensureCatalog();

        assertEquals(200, get(httpPort, "/health").statusCode());
        assertEquals(200, get(httpPort, "/ready").statusCode());
    }

    @Test
    @Order(7)
    void aGapMakesTheInstanceStaleAndNotReadyUntilAFullSnapshotArrives() throws Exception {
        ensureCatalog();
        long next = seq + 1;
        try (EventStream events = new EventStream()) {
            assertTrue(events.send(next, "MAT-02", 4).hasAck());
            assertTrue(events.send(next, "MAT-02", 4).hasAck(), "un duplicado se confirma sin reaplicarse");
            var gap = events.send(next + 5, "MAT-02", 3);
            assertTrue(gap.hasResyncRequired());
            assertEquals(next + 1, gap.getResyncRequired().getExpectedSeq());
            assertEquals(next + 5, gap.getResyncRequired().getReceivedSeq());
        }

        await("la instancia deja de estar lista", () -> get(httpPort, "/ready").statusCode() == 503);
        // Sigue respondiendo, pero avisa que el catálogo puede no estar al día.
        var stale = post("/combinations", token("MAT", "FIS"), "{}");
        assertEquals(200, stale.statusCode(), stale.body());
        assertTrue(json(stale).get("stale").asBoolean());
        String gauges = get(adminPort, "/actuator/prometheus").body();
        assertEquals(1.0, sample(gauges, "sched_replica_stale"));
        assertEquals(0.0, sample(gauges, "sched_replica_ready"));

        // La institución reenvía la carga completa y se recupera.
        loadCatalog();
        await("la instancia vuelve a estar lista", () -> get(httpPort, "/ready").statusCode() == 200);
        assertFalse(json(post("/combinations", token("MAT", "FIS"), "{}")).get("stale").asBoolean());
    }

    @Test
    @Order(8)
    void prometheusExposesTheEngineMetricsOnTheAdminPortWithoutAToken() throws Exception {
        ensureCatalog();
        post("/combinations", token("MAT", "FIS"), "{}");

        var prometheus = get(adminPort, "/actuator/prometheus");

        assertEquals(200, prometheus.statusCode());
        assertTrue(prometheus.headers().firstValue("Content-Type").orElseThrow().startsWith("text/plain"));
        String text = prometheus.body();
        // Latencia con histograma (SLO de 200 ms), percentiles y etiquetas por endpoint.
        assertTrue(sample(text, "sched_http_latency_seconds_count", "endpoint=\"combinations\"", "status=\"200\"") >= 3);
        assertTrue(sample(text, "sched_http_latency_seconds_count", "endpoint=\"alternatives\"", "status=\"200\"") >= 1);
        sample(text, "sched_http_latency_seconds_bucket", "endpoint=\"combinations\"", "le=\"0.2\"");
        for (String quantile : List.of("0.5", "0.95", "0.99")) {
            sample(text, "sched_http_latency_quantiles_seconds", "endpoint=\"combinations\"", "quantile=\"" + quantile + "\"");
            sample(text, "sched_search_duration_quantiles_seconds", "operation=\"combinations\"", "quantile=\"" + quantile + "\"");
        }
        // Rechazos por tipo.
        for (String status : List.of("401", "400", "413", "422")) {
            assertTrue(sample(text, "sched_http_rejected_total", "status=\"" + status + "\"") >= 1, status);
        }
        // Búsqueda del solver.
        assertTrue(sample(text, "sched_search_duration_seconds_count", "operation=\"combinations\"") >= 3);
        assertTrue(sample(text, "sched_search_nodes_count", "operation=\"combinations\"") >= 3);
        assertEquals(0.0, sample(text, "sched_search_incomplete_total", "operation=\"combinations\""));
        sample(text, "sched_search_empty_total", "operation=\"alternatives\"");
        // Réplica, seguidor e ingesta.
        assertEquals((double) seq, sample(text, "sched_replica_seq"));
        assertEquals(0.0, sample(text, "sched_replica_stale"));
        assertEquals(1.0, sample(text, "sched_replica_ready"));
        assertEquals(0.0, sample(text, "sched_follower_lag"));
        assertTrue(sample(text, "sched_ingest_snapshots_total") >= 2);
        assertTrue(sample(text, "sched_ingest_events_total", "outcome=\"applied\"") >= 1);
        assertTrue(sample(text, "sched_ingest_events_total", "outcome=\"duplicate\"") >= 1);
        assertTrue(sample(text, "sched_ingest_events_total", "outcome=\"gap\"") >= 1);
        assertTrue(sample(text, "sched_ingest_sync_lost_total") >= 1);
        // Métricas de la JVM (las pone Spring Boot).
        sample(text, "jvm_memory_used_bytes");
    }

    @Test
    @Order(9)
    void theAdminPortOnlyServesHealthAndPrometheusAndTheStudentPortDoesNotServeThem() throws Exception {
        assertEquals(200, get(adminPort, "/actuator/health").statusCode());
        assertEquals(200, get(adminPort, "/actuator/prometheus").statusCode());
        for (String other : List.of("/actuator/env", "/actuator/beans", "/actuator/metrics", "/actuator/heapdump",
                "/actuator/loggers", "/actuator/info")) {
            assertEquals(404, get(adminPort, other).statusCode(), other);
        }
        // Las consultas de estudiantes no se sirven en el puerto de administración.
        assertEquals(404, send(adminPort, "POST", "/combinations", token("MAT", "FIS"), "{}", null).statusCode());
        // Y el puerto de los estudiantes no entrega métricas (exige token, y aun con él no existe).
        assertEquals(401, get(httpPort, "/actuator/prometheus").statusCode());
        assertEquals(404, send(httpPort, "GET", "/actuator/prometheus", token("MAT"), null, null).statusCode());
    }

    @Test
    @Order(10)
    void aGeneratedRequestIdIsReturnedWhenTheClientSendsNone() throws Exception {
        var response = get(httpPort, "/health");

        String id = response.headers().firstValue("X-Request-Id").orElseThrow();
        UUID.fromString(id);
        assertNotEquals(id, get(httpPort, "/health").headers().firstValue("X-Request-Id").orElseThrow());
    }

    @Test
    @Order(11)
    void theWebClientIsServedWithoutATokenAndWithSecurityHeadersWhileTheApiStillNeedsOne() throws Exception {
        ensureCatalog();

        for (String path : new String[] {"/app/", "/app/index.html", "/app/styles.css", "/app/js/main.js", "/app/js/logic.js"}) {
            var response = get(httpPort, path);
            assertEquals(200, response.statusCode(), path);
            var headers = response.headers();
            var csp = headers.firstValue("Content-Security-Policy").orElseThrow();
            assertTrue(csp.startsWith("default-src 'self'"), csp);
            assertFalse(csp.contains("unsafe-inline"), csp);
            assertEquals("nosniff", headers.firstValue("X-Content-Type-Options").orElseThrow(), path);
            assertEquals("no-referrer", headers.firstValue("Referrer-Policy").orElseThrow(), path);
            assertFalse(response.body().isBlank(), path);
        }
        var page = get(httpPort, "/app/");
        assertTrue(page.headers().firstValue("Content-Type").orElseThrow().startsWith("text/html"));
        assertTrue(page.body().contains("<script type=\"module\" src=\"js/main.js\">"));
        assertFalse(page.body().matches("(?s).*<script(?![^>]*\\bsrc=)[^>]*>.*"), "no debe haber scripts en línea");
        assertTrue(get(httpPort, "/app/js/main.js").headers().firstValue("Content-Type").orElseThrow().contains("javascript"));
        assertEquals(302, get(httpPort, "/app").statusCode());

        // Lo demás sigue exigiendo el token, aunque se parezca a /app.
        assertEquals(401, post("/combinations", null, "{}").statusCode());
        assertEquals(401, post("/alternatives", null, "{}").statusCode());
        assertEquals(401, send(httpPort, "POST", "/app/index.html", null, "{}", null).statusCode());
        assertEquals(401, get(httpPort, "/app/../combinations").statusCode());
        assertEquals(401, get(httpPort, "/application").statusCode());
        // Y la API no lleva la política de las páginas.
        var api = post("/combinations", token("MAT", "QUI"), "{}");
        assertEquals(200, api.statusCode());
        assertTrue(api.headers().firstValue("Content-Security-Policy").isEmpty());
    }

    @Test
    @Order(12)
    void aTokenWithoutCoursesAndAnEmptyCourseListAnswer400() throws Exception {
        ensureCatalog();

        var emptyToken = post("/combinations", token(), "{}");
        assertEquals(400, emptyToken.statusCode());
        assertEquals("se requiere al menos una asignatura", json(emptyToken).get("message").asString());
        assertEquals(400, post("/alternatives", token(), "{}").statusCode());
    }

    @Test
    @Order(99)
    void noTokenBodyOrStudentDataReachedTheLogsEvenAtDebugLevel() {
        assertFalse(LOGS.list.isEmpty(), "la prueba debe haber capturado logs");
        List<String> secrets = new ArrayList<>(SECRETS);
        secrets.add(STUDENT);
        for (String secret : new ArrayList<>(secrets)) {
            // Cada tramo del token (cabecera, claims, firma) tampoco debe aparecer.
            if (secret.chars().filter(c -> c == '.').count() == 2) {
                secrets.addAll(List.of(secret.split("\\.")));
            }
        }
        for (ILoggingEvent event : new ArrayList<>(LOGS.list)) {
            String line = event.getFormattedMessage() + " " + event.getMDCPropertyMap() + " "
                    + (event.getThrowableProxy() == null ? "" : ThrowableProxyUtil.asString(event.getThrowableProxy()));
            for (String secret : secrets) {
                assertFalse(secret.length() > 3 && line.contains(secret),
                        "el log contiene un dato confidencial (" + secret.substring(0, 6) + "...): " + line);
            }
        }
        // Y el id de correlación sí acompaña a las líneas de las solicitudes rechazadas.
        assertTrue(LOGS.list.stream().anyMatch(e -> "e2e-sin-token".equals(e.getMDCPropertyMap().get("request_id"))),
                "ninguna línea del log lleva el id de correlación de la solicitud sin token");
    }
}
