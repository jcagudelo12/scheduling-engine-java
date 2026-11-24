package sched.server;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import sched.application.AppException;
import sched.application.CatalogReplica;
import sched.application.Readiness;

/**
 * Estado de la réplica local como gauges, leído en cada raspado: {@code sched.replica.seq} (el
 * {@code seq} del catálogo; -1 si aún no hay carga), {@code sched.replica.stale} (1 si está
 * desactualizada) y {@code sched.replica.ready} (1 si la instancia recibe tráfico, igual que
 * {@code /ready}).
 */
final class ReplicaMeters implements MeterBinder {

    private final CatalogReplica replica;
    private final Readiness readiness;

    ReplicaMeters(CatalogReplica replica, Readiness readiness) {
        this.replica = replica;
        this.readiness = readiness;
    }

    @Override
    public void bindTo(MeterRegistry registry) {
        Gauge.builder("sched.replica.seq", replica, ReplicaMeters::seq)
                .description("seq del catálogo en la réplica; -1 si aún no hay carga completa").register(registry);
        Gauge.builder("sched.replica.stale", replica, r -> r.isStale() ? 1 : 0)
                .description("1 si la réplica está desactualizada (ADR 0003)").register(registry);
        Gauge.builder("sched.replica.ready", readiness, r -> r.isReady() ? 1 : 0)
                .description("1 si la instancia está lista para recibir tráfico (/ready)").register(registry);
    }

    private static double seq(CatalogReplica replica) {
        try {
            return replica.read((catalog, _) -> (double) catalog.seq());
        } catch (AppException.CatalogUnavailable _) {
            return -1;
        }
    }
}
