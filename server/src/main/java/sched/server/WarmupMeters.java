package sched.server;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;

/**
 * Estado del calentamiento como gauges: {@code sched.warmup.duration} (segundos que duró; -1 mientras no
 * ha terminado) y {@code sched.warmup.searches} (solicitudes sintéticas ejecutadas). Son aparte de
 * {@code sched.search.*}: el calentamiento no toca las métricas de búsqueda.
 */
final class WarmupMeters implements MeterBinder {

    private final Warmup warmup;

    WarmupMeters(Warmup warmup) {
        this.warmup = warmup;
    }

    @Override
    public void bindTo(MeterRegistry registry) {
        Gauge.builder("sched.warmup.duration", warmup, w -> {
            Warmup.Report report = w.report();
            return report == null ? -1 : report.elapsed().toMillis() / 1000.0;
        }).baseUnit("seconds").description("Duración del calentamiento de la JVM; -1 mientras no ha terminado")
                .register(registry);
        Gauge.builder("sched.warmup.searches", warmup, w -> (double) w.searches())
                .description("Solicitudes sintéticas ejecutadas por el calentamiento").register(registry);
    }
}
