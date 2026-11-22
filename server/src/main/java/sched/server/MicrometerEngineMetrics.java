package sched.server;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import java.time.Duration;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import sched.application.ports.EngineMetrics;

/**
 * Implementación del puerto {@link EngineMetrics} con Micrometer. Solo cantidades y estados:
 * ningún dato del estudiante ni del catálogo llega a una etiqueta.
 *
 * <ul>
 *   <li>{@code sched.search.duration} (histograma con el SLO de 200 ms y, aparte,
 *       {@code .quantiles} con p50/p95/p99; ver {@link LatencyTimer}) y {@code sched.search.nodes}, por operación.
 *   <li>{@code sched.search.incomplete}: búsquedas cortadas por el tiempo máximo
 *       ({@code complete=false}, ADR 0009). {@code sched.search.empty}: sin resultados.
 *   <li>{@code sched.ingest.events{outcome}}, {@code sched.ingest.snapshots},
 *       {@code sched.ingest.sync_lost}.
 *   <li>{@code sched.follower.lag} (gauge) y {@code sched.follower.resyncs}.
 * </ul>
 */
final class MicrometerEngineMetrics implements EngineMetrics {

    private final Map<Operation, LatencyTimer> duration = new EnumMap<>(Operation.class);
    private final Map<Operation, DistributionSummary> nodes = new EnumMap<>(Operation.class);
    private final Map<Operation, Counter> incomplete = new EnumMap<>(Operation.class);
    private final Map<Operation, Counter> empty = new EnumMap<>(Operation.class);
    private final Map<IngestOutcome, Counter> ingest = new EnumMap<>(IngestOutcome.class);
    private final Counter snapshots;
    private final Counter syncLost;
    private final Counter resyncs;
    private final AtomicLong lag = new AtomicLong();

    MicrometerEngineMetrics(MeterRegistry registry) {
        for (Operation operation : Operation.values()) {
            String name = operation.name().toLowerCase();
            duration.put(operation, new LatencyTimer(registry, "sched.search.duration",
                    "Duración de la búsqueda del solver", List.of(Tag.of("operation", name))));
            nodes.put(operation, DistributionSummary.builder("sched.search.nodes")
                    .description("Nodos explorados por búsqueda")
                    .tag("operation", name)
                    .register(registry));
            incomplete.put(operation, Counter.builder("sched.search.incomplete")
                    .description("Búsquedas cortadas por el tiempo máximo (complete=false)")
                    .tag("operation", name)
                    .register(registry));
            empty.put(operation, Counter.builder("sched.search.empty")
                    .description("Búsquedas sin ninguna combinación")
                    .tag("operation", name)
                    .register(registry));
        }
        for (IngestOutcome outcome : IngestOutcome.values()) {
            ingest.put(outcome, Counter.builder("sched.ingest.events")
                    .description("Cambios incrementales de la institución, por resultado")
                    .tag("outcome", outcome.name().toLowerCase())
                    .register(registry));
        }
        snapshots = Counter.builder("sched.ingest.snapshots")
                .description("Cargas completas escritas en el historial").register(registry);
        syncLost = Counter.builder("sched.ingest.sync_lost")
                .description("Avisos de pérdida de sincronía con la institución").register(registry);
        resyncs = Counter.builder("sched.follower.resyncs")
                .description("Veces que el follower empezó a releer el historial").register(registry);
        Gauge.builder("sched.follower.lag", lag, AtomicLong::get)
                .description("Mensajes del historial que el follower aún no lee").register(registry);
    }

    @Override
    public void searchFinished(Operation operation, Duration elapsed, int results, boolean complete, long visited) {
        duration.get(operation).record(elapsed);
        if (visited >= 0) {
            nodes.get(operation).record(visited);
        }
        if (!complete) {
            incomplete.get(operation).increment();
        }
        if (results == 0) {
            empty.get(operation).increment();
        }
    }

    @Override
    public void ingestEvent(IngestOutcome outcome) {
        ingest.get(outcome).increment();
    }

    @Override
    public void snapshotIngested(int sections) {
        snapshots.increment();
    }

    @Override
    public void syncLost() {
        syncLost.increment();
    }

    @Override
    public void followerLag(long pending) {
        lag.set(pending);
    }

    @Override
    public void followerResync() {
        resyncs.increment();
    }
}
