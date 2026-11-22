package sched.application;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Estado de disponibilidad de la instancia, calculado en cada consulta (ADR 0005).
 *
 * <p>Una instancia está <b>lista</b> cuando el follower está al día con el historial, la
 * réplica ya cargó un catálogo y no está desactualizada ({@code stale}). Si pierde la
 * sincronía (hueco, {@code SyncLost}, caída de NATS) deja de estar lista y el balanceador no le
 * envía tráfico; vuelve a estarlo cuando se aplica una carga completa.
 *
 * <p>{@link #isCaughtUp()} es solo la parte del follower. La ingesta lo usa para abrir su gRPC:
 * necesita haber leído el historial, pero no un catálogo fresco (puede estar vacío, o
 * desactualizado a la espera de la carga completa que ella misma debe recibir).
 */
public final class Readiness {

    private final CatalogReplica replica;
    private final AtomicBoolean caughtUp = new AtomicBoolean();

    public Readiness(CatalogReplica replica) {
        this.replica = replica;
    }

    /** La instancia puede recibir tráfico: al día con el historial y con un catálogo fresco. */
    public boolean isReady() {
        if (!caughtUp.get()) {
            return false;
        }
        try {
            return replica.read((_, stale) -> !stale);
        } catch (AppException.CatalogUnavailable _) {
            return false;
        }
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
