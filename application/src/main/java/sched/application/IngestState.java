package sched.application;

import sched.domain.SequenceGapException;

/**
 * Estado de la instancia de ingesta: último {@code seq} aceptado de la institución.
 *
 * <p>Es la única fuente de validación de orden (un solo escritor, ADR 0005). Al arrancar
 * se inicializa desde la réplica local, que ya se puso al día con el historial.
 *
 * <p>El monitor de esta instancia es el candado de la ingesta: los casos de uso lo toman
 * ({@code synchronized (ingest)}) para que validar el {@code seq} y escribir en el historial
 * sea una sola operación atómica. Así dos flujos gRPC simultáneos no pueden escribir en orden
 * invertido. Es aceptable porque hay un solo escritor (ADR 0005) y el candado es reentrante.
 */
public final class IngestState {

    /** Resultado de validar el {@code seq} de un cambio antes de escribirlo en el historial. */
    enum Admission {
        /** Es el siguiente esperado; debe escribirse y luego confirmarse con {@code commit}. */
        NEXT,
        /** Ya se había aceptado; no se escribe de nuevo. */
        DUPLICATE
    }

    private final CatalogReplica replica;
    private Long lastSeq;
    private boolean needsResync;

    public IngestState(CatalogReplica replica) {
        this.replica = replica;
    }

    /**
     * Valida el {@code seq}. No lo consume: el llamador debe tener el candado y llamar a
     * {@link #commit} solo después de escribirlo en el historial.
     */
    synchronized Admission admit(long seq) throws AppException {
        if (lastSeq == null) {
            lastSeq = replica.read((catalog, stale) -> catalog.seq());
        }
        long expected = lastSeq + 1;

        if (seq < expected) {
            return Admission.DUPLICATE;
        }
        if (needsResync || seq > expected) {
            needsResync = true;
            throw new AppException.SequenceGap(new SequenceGapException(expected, seq));
        }
        return Admission.NEXT;
    }

    /** Consume el {@code seq} admitido, una vez escrito con éxito en el historial. */
    synchronized void commit(long seq) {
        lastSeq = seq;
    }

    /** Tras una carga completa escrita en el historial. */
    synchronized void reset(long seq) {
        lastSeq = seq;
        needsResync = false;
    }
}
