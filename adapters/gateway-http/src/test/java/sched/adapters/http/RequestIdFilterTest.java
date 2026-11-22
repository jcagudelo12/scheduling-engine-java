package sched.adapters.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class RequestIdFilterTest {

    private final RequestIdFilter filter = new RequestIdFilter();

    /** Corre el filtro y devuelve la respuesta y el id que vio la cadena en el MDC. */
    private record Result(MockHttpServletResponse response, String mdcInside) {
    }

    private Result call(String requestId) throws Exception {
        var request = new MockHttpServletRequest("GET", "/health");
        if (requestId != null) {
            request.addHeader(RequestIdFilter.HEADER, requestId);
        }
        var response = new MockHttpServletResponse();
        var inside = new AtomicReference<String>();
        filter.doFilter(request, response, (req, res) -> inside.set(MDC.get(RequestIdFilter.MDC_KEY)));
        return new Result(response, inside.get());
    }

    @Test
    void aValidRequestIdIsKeptReturnedAndPlacedInTheMdc() throws Exception {
        Result result = call("abc-123_X.9");

        assertEquals("abc-123_X.9", result.response().getHeader("X-Request-Id"));
        assertEquals("abc-123_X.9", result.mdcInside());
    }

    @Test
    void aMissingRequestIdIsGenerated() throws Exception {
        Result result = call(null);

        String id = result.response().getHeader("X-Request-Id");
        UUID.fromString(id);
        assertEquals(id, result.mdcInside());
        assertNotEquals(id, call(null).response().getHeader("X-Request-Id"));
    }

    @Test
    void anUnsafeRequestIdIsReplaced() throws Exception {
        for (String unsafe : new String[] {"", "con espacios", "linea\nfalsa", "a".repeat(65), "<script>", "id;drop"}) {
            String id = call(unsafe).response().getHeader("X-Request-Id");
            assertTrue(id.matches("[0-9a-f-]{36}"), unsafe + " -> " + id);
        }
    }

    @Test
    void theMdcIsClearedAfterwardsEvenOnErrors() {
        var request = new MockHttpServletRequest("GET", "/health");

        assertThrows(IllegalStateException.class, () -> filter.doFilter(request, new MockHttpServletResponse(),
                (req, res) -> {
                    throw new IllegalStateException("falla");
                }));

        assertNull(MDC.get(RequestIdFilter.MDC_KEY));
    }

    @Test
    void itRunsBeforeAnyOtherFilter() {
        assertEquals(org.springframework.core.Ordered.HIGHEST_PRECEDENCE, filter.getOrder());
    }
}
