package sched.adapters.http.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.security.KeyPair;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import org.junit.jupiter.api.Test;
import sched.adapters.http.auth.InvalidTokenException.Reason;

class JwtVerifierTest {

    private static final Instant NOW = Instant.parse("2026-11-22T12:00:00Z");

    private final KeyPair key = TestTokens.newKeyPair();
    private final KeyPair otherKey = TestTokens.newKeyPair();
    private final JwtVerifier verifier = new JwtVerifier(
            Map.of("k1", key.getPublic()),
            new JwtVerifier.Settings(TestTokens.ISSUER, TestTokens.AUDIENCE, Duration.ofSeconds(30), Duration.ofMinutes(15)),
            Clock.fixed(NOW, ZoneOffset.UTC));

    private Map<String, Object> claims() {
        return TestTokens.claims(NOW, "est-001", java.util.List.of("MAT101", "FIS101"));
    }

    private String token(Map<String, Object> claims) {
        return TestTokens.sign(key.getPrivate(), "k1", claims);
    }

    private Reason rejected(String token) {
        return assertThrows(InvalidTokenException.class, () -> verifier.verify(token)).reason();
    }

    @Test
    void validTokenYieldsTheStudentAndCourses() throws Exception {
        var claims = verifier.verify(token(claims()));

        assertEquals("est-001", claims.subject());
        assertEquals(java.util.List.of("MAT101", "FIS101"), claims.courses());
        assertEquals("k1", claims.kid());
    }

    @Test
    void audienceMayBeAnArray() throws Exception {
        var c = claims();
        c.put("aud", java.util.List.of("otra", TestTokens.AUDIENCE));

        assertEquals("est-001", verifier.verify(token(c)).subject());
    }

    @Test
    void tamperedSignatureIsRejected() {
        String valid = token(claims());
        String tampered = valid.substring(0, valid.length() - 2) + (valid.endsWith("AA") ? "BB" : "AA");

        assertEquals(Reason.BAD_SIGNATURE, rejected(tampered));
    }

    @Test
    void tamperedPayloadIsRejected() {
        String[] parts = token(claims()).split("\\.");
        var evil = claims();
        evil.put("courses", java.util.List.of("MAT101", "FIS101", "TOP999"));
        String forged = parts[0] + "." + TestTokens.b64(
                tools.jackson.databind.json.JsonMapper.builder().build().writeValueAsBytes(evil)) + "." + parts[2];

        assertEquals(Reason.BAD_SIGNATURE, rejected(forged));
    }

    @Test
    void signatureFromAnotherKeyIsRejected() {
        assertEquals(Reason.BAD_SIGNATURE, rejected(TestTokens.sign(otherKey.getPrivate(), "k1", claims())));
    }

    @Test
    void onlyEdDsaIsAccepted() {
        byte[] fake = new byte[64];
        for (String alg : new String[] {"none", "None", "HS256", "RS256", "ES256", "EdDSA ", "eddsa", "Ed25519"}) {
            assertEquals(Reason.UNSUPPORTED_ALGORITHM,
                    rejected(TestTokens.unsigned(TestTokens.header(alg, "k1"), claims(), fake)), alg);
        }
        // alg ausente o no textual
        var noAlg = TestTokens.header("EdDSA", "k1");
        noAlg.remove("alg");
        assertEquals(Reason.UNSUPPORTED_ALGORITHM, rejected(TestTokens.unsigned(noAlg, claims(), fake)));
        // "none" sin firma (tercer segmento vacío)
        String noSig = TestTokens.b64("{\"alg\":\"none\"}".getBytes()) + "." + TestTokens.b64("{}".getBytes()) + ".";
        assertEquals(Reason.MALFORMED, rejected(noSig));
    }

    @Test
    void hs256SignedWithThePublicKeyAsSecretIsRejected() throws Exception {
        // Ataque clásico de confusión de algoritmos: HMAC con la llave pública como secreto.
        var mac = javax.crypto.Mac.getInstance("HmacSHA256");
        mac.init(new javax.crypto.spec.SecretKeySpec(key.getPublic().getEncoded(), "HmacSHA256"));
        String input = TestTokens.b64(tools.jackson.databind.json.JsonMapper.builder().build()
                .writeValueAsBytes(TestTokens.header("HS256", "k1"))) + "."
                + TestTokens.b64(tools.jackson.databind.json.JsonMapper.builder().build().writeValueAsBytes(claims()));
        String token = input + "." + TestTokens.b64(mac.doFinal(input.getBytes()));

        assertEquals(Reason.UNSUPPORTED_ALGORITHM, rejected(token));
    }

    @Test
    void unknownKidIsRejected() {
        assertEquals(Reason.UNKNOWN_KID, rejected(TestTokens.sign(key.getPrivate(), "otro", claims())));
    }

    @Test
    void missingKidIsAcceptedOnlyWithASingleConfiguredKey() throws Exception {
        String withoutKid = TestTokens.sign(key.getPrivate(), TestTokens.header("EdDSA", null), claims());
        assertEquals("est-001", verifier.verify(withoutKid).subject());

        var two = new JwtVerifier(Map.of("k1", key.getPublic(), "k2", otherKey.getPublic()),
                new JwtVerifier.Settings(TestTokens.ISSUER, TestTokens.AUDIENCE, Duration.ofSeconds(30),
                        Duration.ofMinutes(15)),
                Clock.fixed(NOW, ZoneOffset.UTC));
        assertEquals(Reason.UNKNOWN_KID,
                assertThrows(InvalidTokenException.class, () -> two.verify(withoutKid)).reason());
    }

    @Test
    void rotationAcceptsTokensSignedWithEitherKey() throws Exception {
        var rotating = new JwtVerifier(Map.of("old", key.getPublic(), "new", otherKey.getPublic()),
                new JwtVerifier.Settings(TestTokens.ISSUER, TestTokens.AUDIENCE, Duration.ofSeconds(30),
                        Duration.ofMinutes(15)),
                Clock.fixed(NOW, ZoneOffset.UTC));

        assertEquals("old", rotating.verify(TestTokens.sign(key.getPrivate(), "old", claims())).kid());
        assertEquals("new", rotating.verify(TestTokens.sign(otherKey.getPrivate(), "new", claims())).kid());
        // Cada kid solo valida con su propia llave.
        assertEquals(Reason.BAD_SIGNATURE, assertThrows(InvalidTokenException.class,
                () -> rotating.verify(TestTokens.sign(key.getPrivate(), "new", claims()))).reason());
    }

    @Test
    void expiredTokenIsRejectedBeyondTheSkew() throws Exception {
        var c = TestTokens.claims(NOW.minusSeconds(700), "est-001", java.util.List.of("MAT101"));
        // exp = NOW - 100 s: vencido incluso con 30 s de tolerancia
        assertEquals(Reason.EXPIRED, rejected(token(c)));

        // exp = NOW - 10 s: dentro de la tolerancia
        c.put("exp", NOW.minusSeconds(10).getEpochSecond());
        assertEquals("est-001", verifier.verify(token(c)).subject());
        // exp = NOW - 30 s: justo en el límite, ya vencido
        c.put("exp", NOW.minusSeconds(30).getEpochSecond());
        assertEquals(Reason.EXPIRED, rejected(token(c)));
    }

    @Test
    void notBeforeInTheFutureIsRejected() throws Exception {
        var c = claims();
        c.put("nbf", NOW.plusSeconds(120).getEpochSecond());
        assertEquals(Reason.NOT_YET_VALID, rejected(token(c)));

        c.put("nbf", NOW.plusSeconds(20).getEpochSecond()); // dentro de la tolerancia
        assertEquals("est-001", verifier.verify(token(c)).subject());
    }

    @Test
    void issuedInTheFutureIsRejected() {
        var c = TestTokens.claims(NOW.plusSeconds(300), "est-001", java.util.List.of("MAT101"));
        assertEquals(Reason.NOT_YET_VALID, rejected(token(c)));
    }

    @Test
    void wrongIssuerOrAudienceIsRejected() {
        var c = claims();
        c.put("iss", "otra-institucion");
        assertEquals(Reason.ISSUER, rejected(token(c)));

        c = claims();
        c.put("aud", "otro-servicio");
        assertEquals(Reason.AUDIENCE, rejected(token(c)));

        c = claims();
        c.remove("aud");
        assertEquals(Reason.AUDIENCE, rejected(token(c)));
    }

    @Test
    void excessiveLifetimeIsRejected() throws Exception {
        var c = claims();
        c.put("exp", NOW.plusSeconds(15 * 60 + 1).getEpochSecond());
        assertEquals(Reason.LIFETIME, rejected(token(c)));

        c.put("exp", NOW.plusSeconds(15 * 60).getEpochSecond()); // exactamente el tope
        assertEquals("est-001", verifier.verify(token(c)).subject());
    }

    @Test
    void expAndIatAreMandatory() {
        var c = claims();
        c.remove("exp");
        assertEquals(Reason.MISSING_CLAIM, rejected(token(c)));

        c = claims();
        c.remove("iat");
        assertEquals(Reason.MISSING_CLAIM, rejected(token(c)));

        c = claims();
        c.put("exp", "mañana");
        assertEquals(Reason.MISSING_CLAIM, rejected(token(c)));
    }

    @Test
    void subjectAndCoursesAreMandatoryAndWellTyped() {
        var c = claims();
        c.remove("sub");
        assertEquals(Reason.MISSING_CLAIM, rejected(token(c)));

        c = claims();
        c.put("courses", "MAT101");
        assertEquals(Reason.MISSING_CLAIM, rejected(token(c)));

        c = claims();
        c.put("courses", java.util.List.of(1, 2));
        assertEquals(Reason.MISSING_CLAIM, rejected(token(c)));
    }

    @Test
    void tokensOverTheSizeLimitAreRejectedWithoutDecoding() {
        assertEquals(Reason.TOO_LARGE, rejected("a".repeat(JwtVerifier.MAX_TOKEN_BYTES + 1)));
        var c = claims();
        c.put("courses", java.util.Collections.nCopies(2000, "MAT101"));
        assertEquals(Reason.TOO_LARGE, rejected(token(c)));
    }

    @Test
    void malformedTokensAreRejected() {
        for (String bad : new String[] {"", "abc", "a.b", "a.b.c.d", "..", "a..c", "!!!.???.***",
                TestTokens.b64("[]".getBytes()) + "." + TestTokens.b64("{}".getBytes()) + "." + TestTokens.b64(new byte[64])}) {
            rejected(bad);
        }
        assertThrows(InvalidTokenException.class, () -> verifier.verify(null));
    }

    @Test
    void dangerousHeadersAreRejected() {
        var h = TestTokens.header("EdDSA", "k1");
        h.put("crit", java.util.List.of("exp"));
        assertEquals(Reason.UNSUPPORTED_HEADER, rejected(TestTokens.sign(key.getPrivate(), h, claims())));

        h = TestTokens.header("EdDSA", "k1");
        h.put("jku", "http://evil/keys");
        assertEquals(Reason.UNSUPPORTED_HEADER, rejected(TestTokens.sign(key.getPrivate(), h, claims())));

        h = TestTokens.header("EdDSA", "k1");
        h.put("typ", "JOSE+JSON");
        assertEquals(Reason.UNSUPPORTED_HEADER, rejected(TestTokens.sign(key.getPrivate(), h, claims())));
    }

    @Test
    void theErrorNeverCarriesTheTokenContent() {
        var c = claims();
        c.put("sub", "SECRETO-DEL-ESTUDIANTE");
        c.put("iss", "otra");
        var error = assertThrows(InvalidTokenException.class, () -> verifier.verify(token(c)));

        assertEquals(false, error.getMessage().contains("SECRETO"));
        assertEquals("emisor_incorrecto", error.getMessage());
    }
}
