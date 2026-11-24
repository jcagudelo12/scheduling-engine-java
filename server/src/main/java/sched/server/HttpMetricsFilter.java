package sched.server;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.core.Ordered;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Mide la latencia de {@code /combinations} y {@code /alternatives} y cuenta los rechazos 4xx.
 * Va antes de la autenticación y de los límites, para ver también lo que ellos rechazan.
 *
 * <ul>
 *   <li>{@code sched.http.latency{endpoint,status}}: histograma con cubetas, entre ellas el SLO de
 *       200 ms (escenario E2), y {@code sched.http.latency.quantiles} con p50/p95/p99 (ver
 *       {@link LatencyTimer}). Las etiquetas son de cardinalidad fija.
 *   <li>{@code sched.http.rejected{status}} para 400, 401, 413 y 422 (cualquier ruta).
 * </ul>
 *
 * <p>No mira la ruta con parámetros, la consulta ni el cuerpo: nunca registra datos de la solicitud.
 */
final class HttpMetricsFilter extends OncePerRequestFilter implements Ordered {

    private static final Map<String, String> ENDPOINTS = Map.of(
            "/combinations", "combinations",
            "/alternatives", "alternatives");

    private final MeterRegistry registry;
    private final Map<String, LatencyTimer> timers = new ConcurrentHashMap<>();
    private final Map<Integer, Counter> rejections = new ConcurrentHashMap<>();

    HttpMetricsFilter(MeterRegistry registry) {
        this.registry = registry;
    }

    /** Después del id de correlación y antes de la autenticación. */
    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 10;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String endpoint = ENDPOINTS.get(request.getRequestURI());
        long started = System.nanoTime();
        int status = HttpServletResponse.SC_INTERNAL_SERVER_ERROR;
        try {
            chain.doFilter(request, response);
            status = response.getStatus();
        } finally {
            if (endpoint != null) {
                timer(endpoint, status).record(Duration.ofNanos(System.nanoTime() - started));
            }
            if (status == 400 || status == 401 || status == 413 || status == 422) {
                rejections.computeIfAbsent(status, s -> Counter.builder("sched.http.rejected")
                        .description("Solicitudes rechazadas por el motor, por código HTTP")
                        .tag("status", String.valueOf(s)).register(registry)).increment();
            }
        }
    }

    private LatencyTimer timer(String endpoint, int status) {
        return timers.computeIfAbsent(endpoint + ' ' + status, _ -> new LatencyTimer(registry, "sched.http.latency",
                "Latencia de las consultas de horarios",
                List.of(Tag.of("endpoint", endpoint), Tag.of("status", String.valueOf(status)))));
    }
}
