package sched.server;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.util.List;

/**
 * Una latencia con las dos formas que usa Prometheus, porque Micrometer no publica ambas en un
 * mismo medidor:
 *
 * <ul>
 *   <li>{@code <nombre>_seconds_bucket}: histograma con cubetas fijas, entre ellas el SLO de 200 ms
 *       (escenario E2). Se suma entre réplicas y de él salen los percentiles de todo el servicio
 *       con {@code histogram_quantile}.
 *   <li>{@code <nombre>_quantiles_seconds{quantile="0.5|0.95|0.99"}}: p50, p95 y p99 de esa
 *       réplica en una ventana reciente; sirven para verlos directamente, pero no se promedian.
 * </ul>
 */
final class LatencyTimer {

    /** Cubetas del histograma; el SLO de /combinations y /alternatives es 200 ms. */
    static final Duration[] BUCKETS = {
        Duration.ofMillis(10), Duration.ofMillis(25), Duration.ofMillis(50), Duration.ofMillis(100),
        Duration.ofMillis(200), Duration.ofMillis(500), Duration.ofSeconds(1)
    };
    static final double[] PERCENTILES = {0.5, 0.95, 0.99};

    private final Timer histogram;
    private final Timer quantiles;

    LatencyTimer(MeterRegistry registry, String name, String description, List<Tag> tags) {
        histogram = Timer.builder(name).description(description).tags(tags)
                .serviceLevelObjectives(BUCKETS).register(registry);
        quantiles = Timer.builder(name + ".quantiles")
                .description(description + " (p50, p95 y p99 de esta réplica)").tags(tags)
                .publishPercentiles(PERCENTILES).register(registry);
    }

    void record(Duration elapsed) {
        histogram.record(elapsed);
        quantiles.record(elapsed);
    }
}
