package sched.simulation;

import io.grpc.ChannelCredentials;
import io.grpc.Grpc;
import io.grpc.InsecureChannelCredentials;
import io.grpc.ManagedChannel;
import io.grpc.TlsChannelCredentials;
import io.grpc.stub.StreamObserver;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import sched.domain.Course;
import sched.domain.EnrollmentRules;
import sched.domain.SectionSeats;
import sched.proto.Convert;
import sched.proto.institution.v1.CatalogChunk;
import sched.proto.institution.v1.CatalogEvent;
import sched.proto.institution.v1.CatalogEventsServiceGrpc;
import sched.proto.institution.v1.CatalogServiceGrpc;
import sched.proto.institution.v1.LoadCatalogResponse;
import sched.proto.institution.v1.SeatsChanged;
import sched.proto.institution.v1.SyncStatus;

/**
 * Institución simulada: habla con la instancia de ingesta por el contrato gRPC real, como lo
 * haría el SDK de la institución.
 */
public final class FakeInstitution implements AutoCloseable {

    private static final int SECTIONS_PER_CHUNK = 100;
    private static final long TIMEOUT_SECONDS = 30;

    private final ManagedChannel channel;

    /** Sin TLS: solo para un motor con {@code SCHED_GRPC_TLS=disabled}. */
    public FakeInstitution(String target) {
        this(target, InsecureChannelCredentials.create());
    }

    /**
     * {@code target}: dirección de la ingesta, con o sin {@code http://} (ej. localhost:50051).
     * {@code credentials}: ver {@link #mutualTls}.
     */
    public FakeInstitution(String target, ChannelCredentials credentials) {
        this.channel = Grpc.newChannelBuilder(target.replaceFirst("^https?://", ""), credentials).build();
    }

    /**
     * TLS con autenticación mutua, como lo exige la ingesta: el certificado y la llave (PKCS#8) de
     * la institución, y la CA que firmó el certificado del servidor.
     */
    public static ChannelCredentials mutualTls(Path clientCert, Path clientKey, Path serverCa) throws IOException {
        return TlsChannelCredentials.newBuilder()
                .keyManager(clientCert.toFile(), clientKey.toFile())
                .trustManager(serverCa.toFile())
                .build();
    }

    /**
     * Carga completa del catálogo, en bloques del mismo {@code seq}. Los cursos se reparten entre
     * los bloques y las reglas del periodo van una sola vez, en el primero.
     */
    public LoadCatalogResponse load(long seq, List<SectionSeats> sections, List<Course> courses, EnrollmentRules rules)
            throws Exception {
        CompletableFuture<LoadCatalogResponse> result = new CompletableFuture<>();
        StreamObserver<CatalogChunk> chunks = CatalogServiceGrpc.newStub(channel).loadCatalog(new StreamObserver<>() {
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

        int size = Math.max(1, Math.max(sections.size(), courses.size()));
        for (int from = 0; from < size; from += SECTIONS_PER_CHUNK) {
            CatalogChunk.Builder chunk = CatalogChunk.newBuilder().setSeq(seq);
            for (SectionSeats s : slice(sections, from)) {
                chunk.addSections(Convert.sectionToPb(s.section(), s.available()));
            }
            for (Course course : slice(courses, from)) {
                chunk.addCourses(Convert.courseToPb(course));
            }
            if (from == 0) {
                chunk.setRules(Convert.rulesToPb(rules));
            }
            chunks.onNext(chunk.build());
        }
        chunks.onCompleted();
        return result.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }

    private static <T> List<T> slice(List<T> items, int from) {
        int start = Math.min(items.size(), from);
        return items.subList(start, Math.min(items.size(), start + SECTIONS_PER_CHUNK));
    }

    /** Envía un cambio de cupo y devuelve la respuesta del motor. */
    public SyncStatus seats(long seq, String sectionId, int available) throws Exception {
        CompletableFuture<SyncStatus> result = new CompletableFuture<>();
        StreamObserver<CatalogEvent> events = CatalogEventsServiceGrpc.newStub(channel).syncChanges(new StreamObserver<>() {
            @Override
            public void onNext(SyncStatus status) {
                result.complete(status);
            }

            @Override
            public void onError(Throwable error) {
                result.completeExceptionally(error);
            }

            @Override
            public void onCompleted() {
            }
        });

        events.onNext(CatalogEvent.newBuilder()
                .setSeq(seq)
                .setSeatsChanged(SeatsChanged.newBuilder().setSectionId(sectionId).setAvailableSeats(available))
                .build());
        SyncStatus status = result.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        events.onCompleted();
        return status;
    }

    /**
     * Abre un único stream de cambios para enviar muchos eventos de cupo seguidos (ráfaga del
     * pico). Los {@code seq} deben ser consecutivos; el motor responde a cada evento.
     */
    public SeatStream openSeatStream() {
        return new SeatStream();
    }

    /** Stream de cambios de cupo con registro de las respuestas del motor y su latencia. */
    public final class SeatStream implements AutoCloseable {

        private final Map<Long, Long> sentAtNanos = new ConcurrentHashMap<>();
        private final ConcurrentLinkedQueue<Long> ackNanos = new ConcurrentLinkedQueue<>();
        private final AtomicInteger sent = new AtomicInteger();
        private final AtomicInteger acks = new AtomicInteger();
        private final AtomicInteger notAcked = new AtomicInteger();
        private final AtomicBoolean closed = new AtomicBoolean();
        private final StreamObserver<CatalogEvent> events;

        private SeatStream() {
            this.events = CatalogEventsServiceGrpc.newStub(channel).syncChanges(new StreamObserver<>() {
                @Override
                public void onNext(SyncStatus status) {
                    if (status.getStatusCase() == SyncStatus.StatusCase.ACK) {
                        Long start = sentAtNanos.remove(status.getAck().getSeq());
                        if (start != null) {
                            ackNanos.add(System.nanoTime() - start);
                        }
                        acks.incrementAndGet();
                    } else {
                        notAcked.incrementAndGet();
                    }
                }

                @Override
                public void onError(Throwable error) {
                    notAcked.incrementAndGet();
                }

                @Override
                public void onCompleted() {
                }
            });
        }

        /** Envía un cambio de cupo sin esperar la respuesta. */
        public synchronized void send(long seq, String sectionId, int available) {
            sentAtNanos.put(seq, System.nanoTime());
            sent.incrementAndGet();
            events.onNext(CatalogEvent.newBuilder()
                    .setSeq(seq)
                    .setSeatsChanged(SeatsChanged.newBuilder().setSectionId(sectionId).setAvailableSeats(available))
                    .build());
        }

        public int sent() {
            return sent.get();
        }

        public int acks() {
            return acks.get();
        }

        /** Respuestas que no fueron {@code Ack} (rechazos, resincronización o error del stream). */
        public int notAcked() {
            return notAcked.get();
        }

        /** Latencias de confirmación, en milisegundos, en el orden de llegada. */
        public List<Double> ackLatenciesMs() {
            return ackNanos.stream().map(n -> n / 1_000_000.0).toList();
        }

        /** Espera las respuestas pendientes (hasta 10 s) y cierra el stream. Se puede llamar más de una vez. */
        @Override
        public void close() throws InterruptedException {
            if (!closed.compareAndSet(false, true)) {
                return;
            }
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (acks.get() + notAcked.get() < sent.get() && System.nanoTime() < deadline) {
                Thread.sleep(20);
            }
            events.onCompleted();
        }
    }

    @Override
    public void close() throws InterruptedException {
        channel.shutdown().awaitTermination(5, TimeUnit.SECONDS);
    }
}
