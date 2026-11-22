package sched.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.grpc.ChannelCredentials;
import io.grpc.Grpc;
import io.grpc.InsecureChannelCredentials;
import io.grpc.ManagedChannel;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.TlsChannelCredentials;
import io.grpc.stub.StreamObserver;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sched.adapters.grpc.catalog.CatalogGrpcService;
import sched.adapters.inmemory.InMemoryCatalogLog;
import sched.application.ApplyLogEntry;
import sched.application.CatalogReplica;
import sched.application.IngestCatalog;
import sched.application.IngestState;
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
 * El gRPC de ingesta con TLS y autenticación mutua: solo conecta quien presenta un certificado de
 * cliente firmado por la CA de la institución y confía en el servidor. Usa el servidor real
 * ({@link IngestGrpcServer} y {@link GrpcCredentials}) en un puerto libre y certificados
 * generados en la prueba.
 */
class IngestGrpcMutualTlsTest {

    @TempDir
    Path dir;

    private TestPki.Identity ca;
    private Path caCert;
    private IngestGrpcServer server;
    private int port;

    @BeforeEach
    void startServer() throws Exception {
        ca = TestPki.newCa("sched-test-ca");
        caCert = ca.write(dir, "ca").cert();
        var serverPem = TestPki.newServer(ca, "ingest").write(dir, "server");
        server = serverWith(GrpcCredentials.server("required",
                serverPem.cert().toString(), serverPem.key().toString(), caCert.toString()));
        port = server.boundPort();
    }

    @AfterEach
    void stopServer() throws Exception {
        server.stop();
    }

    private IngestGrpcServer serverWith(io.grpc.ServerCredentials credentials) throws Exception {
        CatalogReplica replica = new CatalogReplica();
        Readiness readiness = new Readiness(replica);
        readiness.markCaughtUp();
        IngestGrpcServer started = new IngestGrpcServer(0, credentials, readiness, List.of(new CatalogGrpcService(
                new IngestCatalog(new IngestState(replica), new InMemoryCatalogLog(new ApplyLogEntry(replica))))));
        started.start();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (started.boundPort() < 0) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("el servidor gRPC no abrió el puerto");
            }
            Thread.sleep(20);
        }
        return started;
    }

    private LoadCatalogResponse load(ChannelCredentials credentials, int targetPort) throws Exception {
        ManagedChannel channel = Grpc.newChannelBuilder("localhost:" + targetPort, credentials).build();
        try {
            CompletableFuture<LoadCatalogResponse> result = new CompletableFuture<>();
            StreamObserver<CatalogChunk> chunks = CatalogServiceGrpc.newStub(channel)
                    .withDeadlineAfter(10, TimeUnit.SECONDS)
                    .loadCatalog(new StreamObserver<>() {
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
            Section section = new Section(new SectionId("MAT-01"), new CourseId("MAT"),
                    List.of(TimeSlot.of(Weekday.MONDAY, 420, 540).orElseThrow()), 30);
            chunks.onNext(CatalogChunk.newBuilder().setSeq(1).addSections(Convert.sectionToPb(section, 5)).build());
            chunks.onCompleted();
            return result.get(15, TimeUnit.SECONDS);
        } finally {
            channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    private static void assertConnectionFails(org.junit.jupiter.api.function.Executable call) {
        ExecutionException error = assertThrows(ExecutionException.class, call);
        StatusRuntimeException status = assertInstanceOf(StatusRuntimeException.class, error.getCause());
        assertEquals(Status.Code.UNAVAILABLE, status.getStatus().getCode(), status.getMessage());
    }

    @Test
    void aClientWithAValidCertificateConnectsAndLoads() throws Exception {
        var client = TestPki.newClient(ca, "institucion").write(dir, "client");
        ChannelCredentials credentials = TlsChannelCredentials.newBuilder()
                .trustManager(caCert.toFile()).keyManager(client.cert().toFile(), client.key().toFile()).build();

        LoadCatalogResponse response = load(credentials, port);

        assertEquals(1, response.getSeq());
        assertEquals(1, response.getSectionsLoaded());
    }

    @Test
    void aClientWithoutACertificateFails() throws Exception {
        ChannelCredentials noCertificate = TlsChannelCredentials.newBuilder().trustManager(caCert.toFile()).build();

        assertConnectionFails(() -> load(noCertificate, port));
    }

    @Test
    void aClientWithACertificateFromAnotherCaFails() throws Exception {
        var otherCa = TestPki.newCa("otra-ca");
        var stranger = TestPki.newClient(otherCa, "intruso").write(dir, "intruso");
        ChannelCredentials credentials = TlsChannelCredentials.newBuilder()
                .trustManager(caCert.toFile()).keyManager(stranger.cert().toFile(), stranger.key().toFile()).build();

        assertConnectionFails(() -> load(credentials, port));
    }

    @Test
    void aPlaintextClientFails() {
        assertConnectionFails(() -> load(InsecureChannelCredentials.create(), port));
    }

    @Test
    void aClientThatDoesNotTrustTheServerFails() throws Exception {
        var otherCa = TestPki.newCa("otra-ca");
        var otherCaCert = otherCa.write(dir, "otra-ca").cert();
        var client = TestPki.newClient(ca, "institucion").write(dir, "client");
        ChannelCredentials credentials = TlsChannelCredentials.newBuilder()
                .trustManager(otherCaCert.toFile()).keyManager(client.cert().toFile(), client.key().toFile()).build();

        assertConnectionFails(() -> load(credentials, port));
    }

    @Test
    void disabledModeAcceptsPlaintextClients() throws Exception {
        IngestGrpcServer open = serverWith(GrpcCredentials.server("disabled", "", "", ""));
        try {
            assertEquals(1, load(InsecureChannelCredentials.create(), open.boundPort()).getSectionsLoaded());
        } finally {
            open.stop();
        }
    }

    @Test
    void requiredModeWithoutFilesDoesNotStart() throws Exception {
        var missing = assertThrows(IllegalStateException.class, () -> GrpcCredentials.server("required", "", "", ""));
        assertTrue(missing.getMessage().contains("SCHED_GRPC_TLS_CERT"), missing.getMessage());

        var unreadable = assertThrows(IllegalStateException.class, () -> GrpcCredentials.server(
                "required", dir.resolve("no.crt").toString(), dir.resolve("no.key").toString(), caCert.toString()));
        assertTrue(unreadable.getMessage().contains("no.crt"), unreadable.getMessage());

        assertThrows(IllegalArgumentException.class, () -> GrpcCredentials.server("quiza", "", "", ""));
    }

    @Test
    void invalidPemFilesAreReportedClearly() throws Exception {
        Path junk = java.nio.file.Files.writeString(dir.resolve("junk.pem"), "no es un PEM");

        var error = assertThrows(IllegalStateException.class, () -> GrpcCredentials.server(
                "required", junk.toString(), junk.toString(), junk.toString()));
        assertTrue(error.getMessage().contains("PEM"), error.getMessage());
    }
}
