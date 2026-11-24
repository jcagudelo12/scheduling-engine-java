package sched.application;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Estado de disponibilidad de la instancia, calculado en cada consulta (ADR 0005).
 *
 * <p>Una instancia está <b>lista</b> cuando el follower está al día con el historial, la
 * réplica ya cargó un catálogo, no está desactualizada ({@code stale}) y, si la instancia sirve
 * consultas con calentamiento, la JVM ya está <b>calentada</b> (ver {@link #markWarmedUp()}). Si
 * pierde la sincronía (hueco, {@code SyncLost}, caída de NATS) deja de estar lista y el balanceador
 * no le envía tráfico; vuelve a estarlo cuando se aplica una carga completa.
 *
 * <p>El calentamiento ocurre una sola vez por proceso: una vez marcado, una réplica que queda
 * {@code stale} y se recupera no vuelve a calentarse (el código ya está compilado).
 *
 * <p>{@link #isCaughtUp()} es solo la parte del follower. La ingesta lo usa para abrir su gRPC:
 * necesita haber leído el historial, pero no un catálogo fresco (puede estar vacío, o
 * desactualizado a la espera de la carga completa que ella misma debe recibir).
 */
public final class Readiness {

    private final CatalogReplica replica;
    private final AtomicBoolean caughtUp = new AtomicBoolean();
    private final AtomicBoolean warmedUp;

    /** Sin calentamiento: la instancia ya cuenta como calentada. */
    public Readiness(CatalogReplica replica) {
        this(replica, false);
    }

    /**
     * @param requiresWarmup {@code true} si la instancia no debe declararse lista hasta que alguien
     *     llame a {@link #markWarmedUp()} (rol que sirve consultas con el calentamiento activado)
     */
    public Readiness(CatalogReplica replica, boolean requiresWarmup) {
        this.replica = replica;
        this.warmedUp = new AtomicBoolean(!requiresWarmup);
    }

    /**
     * La instancia puede recibir tráfico: al día con el historial, con un catálogo fresco y
     * calentada.
     */
    public boolean isReady() {
        return isCatalogReady() && warmedUp.get();
    }

    /**
     * Al día con el historial y con un catálogo fresco, sin mirar el calentamiento. Es la condición
     * que dispara el calentamiento.
     */
    public boolean isCatalogReady() {
        if (!caughtUp.get()) {
            return false;
        }
        try {
            return replica.read((_, stale) -> !stale);
        } catch (AppException.CatalogUnavailable _) {
            return false;
        }
    }

    /** El calentamiento terminó (o se descartó): ya no condiciona la disponibilidad. */
    public boolean isWarmedUp() {
        return warmedUp.get();
    }

    /** El calentamiento terminó, con éxito o no; no se vuelve a pedir en la vida del proceso. */
    public void markWarmedUp() {
        warmedUp.set(true);
    }

    /** El follower está conectado y leyó el historial hasta el final. */
    public boolean isCaughtUp() {
        return caughtUp.get();
    }

    /** El follower leyó el historial hasta el final. */
    public void markCaughtUp() {
        caughtUp.set(true);
    }

    /** El follower perdió la conexión o empezó a releer el historial. */
    public void markBehind() {
        caughtUp.set(false);
    }
}
