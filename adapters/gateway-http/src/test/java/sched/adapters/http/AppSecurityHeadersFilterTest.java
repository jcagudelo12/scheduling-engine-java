package sched.adapters.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import sched.application.CatalogReplica;
import sched.application.Readiness;

/** Las cabeceras de seguridad se ponen en /app/** y solo ahí. */
class AppSecurityHeadersFilterTest {

    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new HealthController(new Readiness(new CatalogReplica())))
            .addFilters(new AppSecurityHeadersFilter())
            .build();

    @Test
    void appPagesCarryAStrictPolicyWithoutInlineScripts() throws Exception {
        for (String path : new String[] {"/app", "/app/", "/app/index.html"}) {
            MockHttpServletResponse response = mvc.perform(get(path)).andReturn().getResponse();

            String csp = response.getHeader("Content-Security-Policy");
            assertTrue(csp.startsWith("default-src 'self'"), csp);
            assertFalse(csp.contains("unsafe-inline"), csp);
            assertFalse(csp.contains("http"), csp);
            assertEquals("nosniff", response.getHeader("X-Content-Type-Options"), path);
            assertEquals("no-referrer", response.getHeader("Referrer-Policy"), path);
        }
    }

    @Test
    void otherResponsesAreNotTouched() throws Exception {
        MockHttpServletResponse response = mvc.perform(get("/health")).andReturn().getResponse();

        assertNull(response.getHeader("Content-Security-Policy"));
        assertNull(mvc.perform(post("/combinations")).andReturn().getResponse().getHeader("Referrer-Policy"));
    }
}
