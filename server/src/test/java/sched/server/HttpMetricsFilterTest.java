package sched.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.search.MeterNotFoundException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class HttpMetricsFilterTest {

    private final MeterRegistry registry = new SimpleMeterRegistry();
    private final HttpMetricsFilter filter = new HttpMetricsFilter(registry);

    private void call(String path, int status) throws Exception {
        var request = new MockHttpServletRequest("POST", path);
        request.setRequestURI(path);
        filter.doFilter(request, new MockHttpServletResponse(), (req, res) -> ((HttpServletResponse) res).setStatus(status));
    }

    @Test
    void latencyIsRecordedPerEndpointAndStatus() throws Exception {
        call("/combinations", 200);
        call("/combinations", 200);
        call("/alternatives", 200);
        call("/combinations", 401);

        assertEquals(2, registry.get("sched.http.latency").tag("endpoint", "combinations").tag("status", "200").timer().count());
        assertEquals(1, registry.get("sched.http.latency").tag("endpoint", "alternatives").tag("status", "200").timer().count());
        assertEquals(1, registry.get("sched.http.latency").tag("endpoint", "combinations").tag("status", "401").timer().count());
    }

    @Test
    void otherPathsAreNotTimedSoTheCardinalityStaysFixed() throws Exception {
        call("/health", 200);
        call("/ready", 503);
        call("/cualquier/cosa/" + System.nanoTime(), 404);

        assertThrows(MeterNotFoundException.class, () -> registry.get("sched.http.latency").timer());
    }

    @Test
    void rejectionsAreCountedByStatus() throws Exception {
        for (int status : new int[] {401, 401, 400, 413, 422, 200, 404, 503}) {
            call("/combinations", status);
        }

        assertEquals(2, registry.get("sched.http.rejected").tag("status", "401").counter().count());
        assertEquals(1, registry.get("sched.http.rejected").tag("status", "400").counter().count());
        assertEquals(1, registry.get("sched.http.rejected").tag("status", "413").counter().count());
        assertEquals(1, registry.get("sched.http.rejected").tag("status", "422").counter().count());
        assertThrows(MeterNotFoundException.class, () -> registry.get("sched.http.rejected").tag("status", "404").counter());
    }

    @Test
    void rejectionsOfAnyPathAreCounted() throws Exception {
        call("/otra-ruta", 401);

        assertEquals(1, registry.get("sched.http.rejected").tag("status", "401").counter().count());
    }

    @Test
    void anExceptionIsRecordedAs500AndPropagated() {
        var request = new MockHttpServletRequest("POST", "/combinations");
        request.setRequestURI("/combinations");

        assertThrows(IllegalStateException.class, () -> filter.doFilter(request, new MockHttpServletResponse(),
                (req, res) -> {
                    throw new IllegalStateException("falla");
                }));

        assertEquals(1, registry.get("sched.http.latency").tag("status", "500").timer().count());
    }

    @Test
    void latencyHasPercentilesAndTheTwoHundredMillisecondBucket() throws Exception {
        call("/combinations", 200);

        var quantiles = registry.get("sched.http.latency.quantiles").timer().takeSnapshot();
        assertEquals(3, quantiles.percentileValues().length);
        var snapshot = registry.get("sched.http.latency").timer().takeSnapshot();
        assertTrue(java.util.Arrays.stream(snapshot.histogramCounts())
                .anyMatch(c -> c.bucket(java.util.concurrent.TimeUnit.MILLISECONDS) == 200.0));
    }
}
