package sched.simulation;

import io.grpc.Grpc;
import io.grpc.InsecureChannelCredentials;
import io.grpc.ManagedChannel;
import io.grpc.stub.StreamObserver;
import java.util.List;
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

    /** {@code target}: dirección de la ingesta, con o sin {@code http://} (ej. localhost:50051). */
    public FakeInstitution(String target) {
        this.channel = Grpc.newChannelBuilder(target.replaceFirst("^https?://", ""), InsecureChannelCredentials.create())
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

    @Override
    public void close() throws InterruptedException {
        channel.shutdown().awaitTermination(5, TimeUnit.SECONDS);
    }
}
