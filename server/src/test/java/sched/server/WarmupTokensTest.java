package sched.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import sched.adapters.http.auth.InvalidTokenException;
import sched.adapters.http.auth.JwtVerifier;
import tools.jackson.databind.json.JsonMapper;

/** La llave efímera del calentamiento solo vale para su propio verificador, nunca para el de producción. */
class WarmupTokensTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static WarmupTokens issue() {
        return WarmupTokens.issue(JSON, List.of("warmup-0", "warmup-1"), List.of(List.of("A", "B"), List.of("C")));
    }

    @Test
    void theWarmupVerifierAcceptsItsOwnTokens() throws Exception {
        WarmupTokens tokens = issue();
        var first = tokens.verifier().verify(tokens.tokens().get(0));
        assertEquals("warmup-0", first.subject());
        assertEquals(List.of("A", "B"), first.courses());
        assertEquals(List.of("C"), tokens.verifier().verify(tokens.tokens().get(1)).courses());
    }

    @Test
    void aProductionVerifierRejectsTheEphemeralTokens() throws Exception {
        WarmupTokens tokens = issue();
        KeyPair production = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        var settings = new JwtVerifier.Settings("institucion", "sched-engine", Duration.ofSeconds(30), Duration.ofMinutes(15));

        // Con otro kid: no se conoce la llave.
        var otherKid = new JwtVerifier(Map.of("k1", production.getPublic()), settings);
        assertEquals(InvalidTokenException.Reason.UNKNOWN_KID,
                assertThrows(InvalidTokenException.class, () -> otherKid.verify(tokens.tokens().get(0))).reason());

        // Aun si la llave de producción se llamara igual y el emisor y la audiencia coincidieran, la firma no cuadra.
        var sameNames = new JwtVerifier(Map.of("warmup", production.getPublic()),
                new JwtVerifier.Settings("sched-warmup", "sched-warmup", Duration.ofSeconds(30), Duration.ofMinutes(15)));
        assertEquals(InvalidTokenException.Reason.BAD_SIGNATURE,
                assertThrows(InvalidTokenException.class, () -> sameNames.verify(tokens.tokens().get(0))).reason());
    }

    @Test
    void eachCallUsesItsOwnKey() throws Exception {
        WarmupTokens one = issue();
        WarmupTokens two = issue();
        assertNotEquals(one.tokens().get(0), two.tokens().get(0));
        assertEquals(InvalidTokenException.Reason.BAD_SIGNATURE,
                assertThrows(InvalidTokenException.class, () -> two.verifier().verify(one.tokens().get(0))).reason());
    }
}
