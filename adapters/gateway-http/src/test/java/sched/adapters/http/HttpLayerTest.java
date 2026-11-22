package sched.adapters.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import sched.application.CatalogReplica;
import sched.application.GenerateCombinations;
import sched.application.SuggestAlternatives;
import sched.solver.SearchResult;
import sched.solver.Solver;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.json.JsonMapper;

/**
 * La capa HTTP sin levantar Spring Boot: lectura del cuerpo (JSON mal formado, tipos incorrectos)
 * y tamaño máximo del cuerpo, con MockMvc independiente y JSON en snake_case como el servidor.
 */
class HttpLayerTest {

    private static final long MAX_BODY = 256;

    private final AtomicInteger searches = new AtomicInteger();
    private final MockMvc mvc;

    HttpLayerTest() {
        Solver solver = problem -> {
            searches.incrementAndGet();
            return new SearchResult(List.of(), true);
        };
        CatalogReplica replica = new CatalogReplica();
        JsonMapper mapper = JsonMapper.builder()
                .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE).build();
        mvc = MockMvcBuilders.standaloneSetup(
                        new CombinationsController(new GenerateCombinations(solver, replica, 10)),
                        new AlternativesController(new SuggestAlternatives(solver, replica, 5)))
                .setControllerAdvice(new HttpExceptionHandler())
                .setMessageConverters(new JacksonJsonHttpMessageConverter(mapper))
                .addFilters(new BodySizeLimitFilter(MAX_BODY))
                .build();
    }

    private MockHttpServletResponse send(String path, String body) throws Exception {
        return mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(body))
                .andReturn().getResponse();
    }

    private void assert400(MockHttpServletResponse response) throws Exception {
        assertEquals(400, response.getStatus());
        assertTrue(response.getContentAsString(StandardCharsets.UTF_8).contains("\"error\":\"invalid_request\""),
                response.getContentAsString());
        assertEquals(0, searches.get());
    }

    @Test
    void malformedJsonAnswers400() throws Exception {
        for (String path : new String[] {"/combinations", "/alternatives"}) {
            assert400(send(path, "{\"student_id\": "));
            assert400(send(path, "no es json"));
        }
    }

    @Test
    void emptyBodyAnswers400() throws Exception {
        assert400(send("/combinations", ""));
        assert400(send("/alternatives", ""));
    }

    @Test
    void wrongTypesAnswer400() throws Exception {
        assert400(send("/combinations", "{\"student_id\":\"s1\",\"eligible_courses\":\"MAT\"}"));
        assert400(send("/combinations", "{\"student_id\":\"s1\",\"eligible_courses\":[\"MAT\"],"
                + "\"avoid\":[{\"day\":\"monday\",\"start_minute\":\"x\",\"end_minute\":5}]}"));
        assert400(send("/alternatives", "{\"student_id\":\"s1\",\"eligible_courses\":[\"MAT\"],\"selection\":[1]}"));
        assert400(send("/alternatives", "{\"student_id\":1,\"eligible_courses\":{}}"));
    }

    @Test
    void missingFieldsAnswer400WithAClearMessage() throws Exception {
        var response = send("/combinations", "{\"eligible_courses\":[\"MAT\"]}");
        assert400(response);
        assertTrue(response.getContentAsString().contains("falta student_id"));

        response = send("/alternatives", "{\"student_id\":\"s1\"}");
        assert400(response);
        assertTrue(response.getContentAsString().contains("falta eligible_courses"));

        assert400(send("/combinations", "null"));
        assert400(send("/combinations", "{\"student_id\":null,\"eligible_courses\":null}"));
    }

    @Test
    void aBodyOverTheLimitAnswers413() throws Exception {
        String big = "{\"student_id\":\"s1\",\"eligible_courses\":[\"" + "A".repeat((int) MAX_BODY) + "\"]}";

        var response = send("/combinations", big);

        assertEquals(413, response.getStatus());
        assertTrue(response.getContentAsString().contains("\"error\":\"payload_too_large\""));
        assertEquals(0, searches.get());
    }

    @Test
    void aBodyWithoutContentLengthIsCutWhileReadingAnd413() throws Exception {
        // Sin Content-Length (transferencia por trozos) el filtro cuenta al leer.
        String big = "{\"student_id\":\"s1\",\"eligible_courses\":[\"" + "A".repeat((int) MAX_BODY) + "\"]}";
        // El filtro se prueba directo con una petición que oculta su Content-Length.
        MockHttpServletRequest raw = new MockHttpServletRequest("POST", "/combinations");
        raw.setContentType("application/json");
        raw.setContent(big.getBytes(StandardCharsets.UTF_8));
        HttpServletRequest hidden = new HttpServletRequestWrapper(raw) {
            @Override
            public long getContentLengthLong() {
                return -1;
            }
        };
        MockHttpServletResponse response = new MockHttpServletResponse();
        BodyTooLargeException[] thrown = new BodyTooLargeException[1];
        new BodySizeLimitFilter(MAX_BODY).doFilter(hidden, response, (req, res) -> {
            try {
                ((HttpServletRequest) req).getInputStream().readAllBytes();
            } catch (BodyTooLargeException e) {
                thrown[0] = e;
            }
        });
        assertTrue(thrown[0] != null, "el flujo debía cortar al pasar el máximo");
        assertEquals(MAX_BODY, thrown[0].maxBytes());

        // Y el manejador lo traduce a 413.
        var answer = new HttpExceptionHandler().handleUnreadable(
                new HttpMessageNotReadableException("x", thrown[0], new ServletServerHttpRequest(raw)));
        assertEquals(413, answer.getStatusCode().value());
        assertEquals("payload_too_large", answer.getBody().error());
    }

    @Test
    void aBodyAtTheLimitPasses() throws Exception {
        String body = "{\"student_id\":\"s1\",\"eligible_courses\":[\"A\"]}";
        assertTrue(body.length() <= MAX_BODY);

        var response = send("/combinations", body);

        // Pasa el filtro y la validación; el catálogo no está cargado: 503, no 400 ni 413.
        assertEquals(503, response.getStatus());
    }

    @Test
    void theFilterRejectsNonPositiveLimits() {
        assertThrows(IllegalArgumentException.class, () -> new BodySizeLimitFilter(0));
    }
}
