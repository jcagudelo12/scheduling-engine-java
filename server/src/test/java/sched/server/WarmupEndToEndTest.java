package sched.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT;

import io.grpc.Grpc;
import io.grpc.InsecureChannelCredentials;
import io.grpc.ManagedChannel;
import io.grpc.stub.StreamObserver;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import sched.application.Readiness;
import sched.domain.CourseId;
import sched.domain.Section;
import sched.domain.SectionId;
import sched.domain.TimeSlot;
import sched.domain.Weekday;
import sched.proto.Convert;
import sched.proto.institution.v1.CatalogChunk;
import sched.proto.institution.v1.CatalogServiceGrpc;
import sched.proto.institution.v1.LoadCatalogResponse;

/**
 * El calentamiento de extremo a extremo: con un catálogo cargado, {@code /ready} responde 503 mientras la
 * JVM se calienta (el mínimo está fijado en 3 s para que se vea) y 200 después; el calentamiento no toca las
 * métricas de búsqueda; y una consulta real con token después sigue funcionando y sí se cuenta.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(classes = SchedServer.class, webEnvironment = RANDOM_PORT, properties = {
        "management.server.port=0",
        "sched.grpc.port=0",
        "sched.grpc.tls=disabled",
        "sched.warmup=on",
        "sched.warmup.min-s=3",
        "sched.warmup.max-s=15",
        "sched.warmup.http=on",
        "sched.warmup.threads=1",
        "logging.level.io.netty=INFO",
        "logging.level.io.grpc=INFO",
        "logging.level.com.github.dockerjava=INFO",
        "logging.level.org.testcontainers=INFO",
})
class WarmupEndToEndTest {

    private static final TestJwt JWT = new TestJwt();

    @Container
    static final GenericContainer<?> NATS = new GenericContainer<>("nats:2")
            .withCommand("-js")
            .withExposedPorts(4222)
            .waitingFor(Wait.forLogMessage(".*Server is ready.*", 1));

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) throws Exception {
        Path keys = Files.createTempDirectory("sched-warmup-keys");
        JWT.writePublicKey(keys);
        String unique = UUID.randomUUID().toString().substring(0, 8);
        registry.add("sched.nats.url", () -> "nats://" + NATS.getHost() + ":" + NATS.getMappedPort(4222));
        registry.add("sched.nats.stream", () -> "WARM_" + unique);
        registry.add("sched.nats.subject-prefix", () -> "warm" + unique);
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

    @Autowired
    private Readiness readiness;

    private final HttpClient http = HttpClient.newHttpClient();

    @Test
    void readyIs503WhileWarmingUpAnd200AfterwithoutTouchingTheSearchMetrics() throws Exception {
        assertEquals(503, get(httpPort, "/ready").statusCode(), "sin catálogo");

        loadCatalog();
        await("el catálogo está al día", readiness::isCatalogReady);
        long caughtUp = System.nanoTime();

        // El calentamiento dura al menos 3 s: la instancia tiene catálogo pero aún no está lista.
        assertFalse(readiness.isReady());
        assertEquals(503, get(httpPort, "/ready").statusCode(), "calentando");
        assertEquals(200, get(httpPort, "/health").statusCode(), "viva aunque no esté lista");
        assertEquals(-1.0, sample(get(adminPort, "/actuator/prometheus").body(), "sched_warmup_duration_seconds"));
        assertEquals(0.0, sample(get(adminPort, "/actuator/prometheus").body(), "sched_replica_ready"));

        await("/ready responde 200", () -> get(httpPort, "/ready").statusCode() == 200);
        assertTrue(Duration.ofNanos(System.nanoTime() - caughtUp).toMillis() >= 2_000, "no se declaró lista antes del mínimo");
        assertTrue(readiness.isWarmedUp());

        String metrics = get(adminPort, "/actuator/prometheus").body();
        assertTrue(sample(metrics, "sched_warmup_duration_seconds") >= 3.0, metrics);
        assertTrue(sample(metrics, "sched_warmup_searches") > 0);
        assertEquals(1.0, sample(metrics, "sched_replica_ready"));
        // Las búsquedas sintéticas no ensucian las métricas de búsqueda ni las HTTP...
        assertEquals(0.0, sumOf(metrics, "sched_search_duration_seconds_count"));
        assertEquals(0.0, sumOf(metrics, "sched_http_latency_seconds_count"));

        // ...y una consulta real con token sí se cuenta.
        var answer = post("/combinations", JWT.token("estudiante-real", List.of("C0", "C1", "C2", "C3")), "{}");
        assertEquals(200, answer.statusCode(), answer.body());
        metrics = get(adminPort, "/actuator/prometheus").body();
        assertEquals(1.0, sumOf(metrics, "sched_search_duration_seconds_count"));
    }

    // Ayudas

    /** 6 cursos de 3 grupos, sin tope de créditos. */
    private void loadCatalog() throws Exception {
        await("el gRPC de ingesta abre su puerto", () -> grpc.boundPort() > 0);
        ManagedChannel channel = Grpc.newChannelBuilder("localhost:" + grpc.boundPort(), InsecureChannelCredentials.create()).build();
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
            var chunk = CatalogChunk.newBuilder().setSeq(1);
            for (int c = 0; c < 6; c++) {
                for (int g = 0; g < 3; g++) {
                    int start = 420 + 120 * ((c + 2 * g) % 6);
                    Section section = new Section(new SectionId("C" + c + "-" + g), new CourseId("C" + c),
                            List.of(TimeSlot.of(Weekday.values()[(c + g) % 5], start, start + 120).orElseThrow()), 30);
                    chunk.addSections(Convert.sectionToPb(section, 10));
                }
            }
            chunks.onNext(chunk.build());
            chunks.onCompleted();
            result.get(30, TimeUnit.SECONDS);
        } finally {
            channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    private static void await(String what, BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("no ocurrió a tiempo: " + what);
            }
            Thread.sleep(20);
        }
    }

    private HttpResponse<String> get(int port, String path) {
        return send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET().build());
    }

    private HttpResponse<String> post(String path, String token, String body) {
        return send(HttpRequest.newBuilder(URI.create("http://localhost:" + httpPort + path))
                .header("Authorization", "Bearer " + token).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build());
    }

    private HttpResponse<String> send(HttpRequest request) {
        try {
            return http.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static double sample(String exposition, String name) {
        for (String line : exposition.split("\n")) {
            if (line.startsWith(name + " ") || line.startsWith(name + "{")) {
                return Double.parseDouble(line.substring(line.lastIndexOf(' ') + 1));
            }
        }
        throw new AssertionError("no hay una muestra " + name + " en:\n" + exposition);
    }

    /** Suma de todas las series de ese nombre (una por etiqueta); 0 si no existe ninguna. */
    private static double sumOf(String exposition, String name) {
        double total = 0;
        for (String line : exposition.split("\n")) {
            if (line.startsWith(name + " ") || line.startsWith(name + "{")) {
                total += Double.parseDouble(line.substring(line.lastIndexOf(' ') + 1));
            }
        }
        return total;
    }
}
