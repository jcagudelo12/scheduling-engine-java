package sched.simulation.experiment;

import java.util.Map;
import sched.adapters.inmemory.InMemoryCatalogLog;
import sched.application.Alternatives;
import sched.application.AppException;
import sched.application.ApplyLogEntry;
import sched.application.CatalogReplica;
import sched.application.Combinations;
import sched.application.GenerateCombinations;
import sched.application.IngestCatalog;
import sched.application.IngestCatalogEvent;
import sched.application.IngestState;
import sched.application.SuggestAlternatives;
import sched.domain.CatalogChange;
import sched.domain.CatalogEvent;
import sched.domain.CourseId;
import sched.domain.SectionId;
import sched.domain.StudentContext;
import sched.solver.BacktrackingSolver;
import sched.solver.ForwardCheckingSolver;
import sched.solver.Solver;

/**
 * El motor evaluado: los casos de uso reales del núcleo sobre una réplica en memoria, alimentada
 * por los mismos casos de uso de ingesta que usaría la plataforma real (carga completa y
 * cambios de cupo con secuencias consecutivas) a través del historial en memoria.
 */
final class EngineBridge {

    /** Carga completa inicial; los cambios de cupo siguen con {@code seq + 1}, {@code + 2}... */
    static final long INITIAL_SEQ = 1;

    /** Respuesta del motor y lo que costó calcularla (0 si el tiempo del motor no se mide). */
    record Answer<T>(T value, long nanos) {
        double seconds() {
            return nanos / 1e9;
        }
    }

    private final CatalogReplica replica = new CatalogReplica();
    private final IngestCatalogEvent ingestEvent;
    private final GenerateCombinations generate;
    private final SuggestAlternatives suggest;
    private final boolean measured;
    private long nextSeq = INITIAL_SEQ;

    EngineBridge(Offer offer, ExperimentConfig.EngineParams params) {
        InMemoryCatalogLog log = new InMemoryCatalogLog(new ApplyLogEntry(replica));
        IngestState state = new IngestState(replica);
        try {
            new IngestCatalog(state, log).execute(INITIAL_SEQ, offer.sections(), offer.courses(), offer.rules());
        } catch (AppException e) {
            throw new IllegalStateException("no se pudo cargar la oferta en el motor", e);
        }
        this.ingestEvent = new IngestCatalogEvent(state, log);
        Solver solver = "backtracking".equals(params.solver()) ? new BacktrackingSolver() : new ForwardCheckingSolver();
        this.generate = new GenerateCombinations(solver, replica, params.maxResults());
        this.suggest = new SuggestAlternatives(solver, replica, params.maxAlternatives());
        this.measured = params.measured();
    }

    /** Secuencia del siguiente cambio de cupo; se asigna al emitirlo, la entrega es en orden. */
    long reserveSeq() {
        return ++nextSeq;
    }

    /** Entrega a la ingesta un cambio de cupo ya numerado. */
    void deliverSeats(long seq, SectionId section, int available) {
        try {
            ingestEvent.execute(new CatalogEvent(seq, new CatalogChange.SeatsChanged(section, available)));
        } catch (AppException e) {
            throw new IllegalStateException("la ingesta rechazó el cambio de cupo seq=" + seq, e);
        }
    }

    Answer<Combinations> combinations(StudentContext student) {
        long started = System.nanoTime();
        Combinations result;
        try {
            result = generate.execute(student);
        } catch (AppException e) {
            throw new IllegalStateException("el motor falló al generar combinaciones: " + e.getMessage(), e);
        }
        long elapsed = System.nanoTime() - started;
        requireFresh(result.stale());
        return new Answer<>(result, measured ? elapsed : 0);
    }

    Answer<Alternatives> alternatives(StudentContext student, Map<CourseId, SectionId> selection) {
        long started = System.nanoTime();
        Alternatives result;
        try {
            result = suggest.execute(student, selection);
        } catch (AppException e) {
            throw new IllegalStateException("el motor falló al sugerir alternativas: " + e.getMessage(), e);
        }
        long elapsed = System.nanoTime() - started;
        requireFresh(result.stale());
        return new Answer<>(result, measured ? elapsed : 0);
    }

    private static void requireFresh(boolean stale) {
        if (stale) {
            throw new IllegalStateException("la réplica del motor quedó desactualizada (stale)");
        }
    }

    /** Al terminar y entregar todos los eventos, la réplica debe tener los mismos cupos que la plataforma. */
    void verifyConsistentWith(Platform platform, Offer offer) {
        try {
            replica.read((catalog, stale) -> {
                if (stale) {
                    throw new IllegalStateException("la réplica terminó desactualizada");
                }
                offer.sections().forEach(s -> {
                    SectionId id = s.section().id();
                    int seen = catalog.available(id).orElseThrow();
                    if (seen != platform.available(id)) {
                        throw new IllegalStateException("la réplica difiere de la plataforma en " + id
                                + ": " + seen + " frente a " + platform.available(id));
                    }
                });
                return null;
            });
        } catch (AppException e) {
            throw new IllegalStateException(e);
        }
    }
}
