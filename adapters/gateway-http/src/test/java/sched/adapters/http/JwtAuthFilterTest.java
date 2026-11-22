package sched.adapters.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import sched.adapters.http.auth.JwtVerifier;
import sched.adapters.http.auth.TestTokens;
import sched.application.CatalogReplica;
import sched.application.GenerateCombinations;
import sched.application.Readiness;
import sched.application.SuggestAlternatives;
import sched.domain.StudentContext;
import sched.solver.Problem;
import sched.solver.SearchResult;
import sched.solver.Solver;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.json.JsonMapper;

/** El filtro del token con la capa HTTP (MockMvc independiente): 401, contexto inyectado y /health sin token. */
class JwtAuthFilterTest {

    private static final Instant NOW = Instant.parse("2026-11-22T12:00:00Z");

    private final KeyPair key = TestTokens.newKeyPair();
    private final AtomicInteger searches = new AtomicInteger();
    private final AtomicReference<Problem> lastProblem = new AtomicReference<>();
    private final MockMvc mvc;

    JwtAuthFilterTest() {
        Solver solver = problem -> {
            searches.incrementAndGet();
            lastProblem.set(problem);
            return new SearchResult(List.of(), true);
        };
        CatalogReplica replica = new CatalogReplica();
        JwtVerifier verifier = new JwtVerifier(Map.of("k1", key.getPublic()),
                new JwtVerifier.Settings(TestTokens.ISSUER, TestTokens.AUDIENCE, Duration.ofSeconds(30),
                        Duration.ofMinutes(15)),
                Clock.fixed(NOW, java.time.ZoneOffset.UTC));
        JsonMapper mapper = JsonMapper.builder()
                .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE).build();
        mvc = MockMvcBuilders.standaloneSetup(
                        new CombinationsController(new GenerateCombinations(solver, replica, 10)),
                        new AlternativesController(new SuggestAlternatives(solver, replica, 5)),
                        new HealthController(new Readiness(replica)))
                .setControllerAdvice(new HttpExceptionHandler())
                .setMessageConverters(new JacksonJsonHttpMessageConverter(mapper))
                .addFilters(new JwtAuthFilter(verifier, RequestLimits.DEFAULTS))
                .build();
    }

    private String token() {
        return TestTokens.sign(key.getPrivate(), "k1",
                TestTokens.claims(NOW, "est-001", List.of("MAT101", "FIS101")));
    }

    private MockHttpServletResponse send(String path, String authorization, String body) throws Exception {
        var request = post(path).contentType(MediaType.APPLICATION_JSON).content(body);
        if (authorization != null) {
            request.header("Authorization", authorization);
        }
        return mvc.perform(request).andReturn().getResponse();
    }

    private void assertUnauthorized(MockHttpServletResponse response) throws Exception {
        assertEquals(401, response.getStatus());
        assertEquals("Bearer", response.getHeader("WWW-Authenticate"));
        assertEquals("{\"error\":\"unauthorized\"}", response.getContentAsString(StandardCharsets.UTF_8));
        assertEquals(0, searches.get());
    }

    @Test
    void validTokenSuppliesTheContextAndTheBodyOnlyBringsPreferences() throws Exception {
        // Sin catálogo cargado: 503 significa que pasó el filtro y la validación (el contexto salió del token).
        assertEquals(503, send("/combinations", "Bearer " + token(), "{}").getStatus());
        assertEquals(503, send("/alternatives", "Bearer " + token(),
                "{\"selection\":{\"MAT101\":\"MAT101-01\"}}").getStatus());
        assertEquals(503, send("/combinations", "bearer " + token(),
                "{\"avoid\":[{\"day\":\"monday\",\"start_minute\":0,\"end_minute\":60}]}").getStatus());
    }

    @Test
    void bodyThatAgreesWithTheTokenIsAccepted() throws Exception {
        String body = "{\"student_id\":\"est-001\",\"eligible_courses\":[\"FIS101\",\"MAT101\"]}";

        assertEquals(503, send("/combinations", "Bearer " + token(), body).getStatus());
    }

    @Test
    void bodyThatDiffersFromTheTokenAnswers400() throws Exception {
        for (String body : new String[] {
                "{\"student_id\":\"otro-estudiante\"}",
                "{\"eligible_courses\":[\"MAT101\",\"FIS101\",\"TOP999\"]}",
                "{\"student_id\":\"est-001\",\"eligible_courses\":[\"MAT101\"]}"}) {
            var response = send("/combinations", "Bearer " + token(), body);
            assertEquals(400, response.getStatus(), body);
            assertTrue(response.getContentAsString().contains("no coincide con"), response.getContentAsString());
            assertEquals(0, searches.get());
        }
    }

    @Test
    void missingOrMalformedAuthorizationHeaderAnswers401() throws Exception {
        assertUnauthorized(send("/combinations", null, "{}"));
        for (String header : new String[] {"", "Bearer", "Bearer ", "Basic dXNlcjpwYXNz", token(),
                "Bearer " + token() + " extra", "Token " + token(), "Bearer  " + token()}) {
            assertUnauthorized(send("/combinations", header, "{}"));
        }
        assertUnauthorized(send("/alternatives", null, "{}"));
    }

    @Test
    void twoAuthorizationHeadersAreRejected() throws Exception {
        var request = post("/combinations").contentType(MediaType.APPLICATION_JSON).content("{}")
                .header("Authorization", "Bearer " + token()).header("Authorization", "Bearer " + token());

        assertUnauthorized(mvc.perform(request).andReturn().getResponse());
    }

    @Test
    void invalidTokensAnswer401WithoutRevealingTheReason() throws Exception {
        var expired = TestTokens.claims(NOW.minusSeconds(3600), "est-001", List.of("MAT101"));
        var wrongIssuer = TestTokens.claims(NOW, "est-001", List.of("MAT101"));
        wrongIssuer.put("iss", "otra");
        String valid = token();
        String[] tokens = {
                valid.substring(0, valid.length() - 3) + "AAA",
                TestTokens.sign(key.getPrivate(), "k1", expired),
                TestTokens.sign(key.getPrivate(), "k1", wrongIssuer),
                TestTokens.sign(key.getPrivate(), "desconocido", TestTokens.claims(NOW, "est-001", List.of("A"))),
                TestTokens.sign(TestTokens.newKeyPair().getPrivate(), "k1", TestTokens.claims(NOW, "est-001", List.of("A"))),
                TestTokens.unsigned(TestTokens.header("none", "k1"), TestTokens.claims(NOW, "est-001", List.of("A")), new byte[0]),
                "x".repeat(9000)};
        for (String token : tokens) {
            assertUnauthorized(send("/combinations", "Bearer " + token, "{}"));
        }
    }

    @Test
    void claimsOutsideTheEngineLimitsAreRejected() throws Exception {
        var tooMany = TestTokens.claims(NOW, "est-001", java.util.Collections.nCopies(13, "MAT101"));
        var badId = TestTokens.claims(NOW, "est 001 con espacios", List.of("MAT101"));

        assertUnauthorized(send("/combinations", "Bearer " + TestTokens.sign(key.getPrivate(), "k1", tooMany), "{}"));
        assertUnauthorized(send("/combinations", "Bearer " + TestTokens.sign(key.getPrivate(), "k1", badId), "{}"));
    }

    @Test
    void healthAndReadyDoNotNeedAToken() throws Exception {
        assertEquals(200, mvc.perform(get("/health")).andReturn().getResponse().getStatus());
        // /ready responde 503 mientras no haya catálogo: llega al controlador, no es un 401.
        assertEquals(503, mvc.perform(get("/ready")).andReturn().getResponse().getStatus());
    }

    @Test
    void otherPathsStillNeedAToken() throws Exception {
        assertEquals(401, mvc.perform(get("/health/x")).andReturn().getResponse().getStatus());
        assertEquals(401, mvc.perform(get("/combinations")).andReturn().getResponse().getStatus());
    }

    @Test
    void withoutTheFilterTheBodyIsStillTheSourceOfTheContext() throws Exception {
        // Modo none: sin filtro, el contexto sale del cuerpo y se valida como siempre.
        Solver solver = problem -> new SearchResult(List.of(), true);
        MockMvc plain = MockMvcBuilders.standaloneSetup(
                        new CombinationsController(new GenerateCombinations(solver, new CatalogReplica(), 10)))
                .setControllerAdvice(new HttpExceptionHandler())
                .setMessageConverters(new JacksonJsonHttpMessageConverter(
                        JsonMapper.builder().propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE).build()))
                .build();

        var response = plain.perform(post("/combinations").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andReturn().getResponse();

        assertEquals(400, response.getStatus());
        assertFalse(response.getContentAsString().contains("token"));
    }
}
