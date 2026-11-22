package sched.simulation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Las herramientas de desarrollo del token producen llaves y firmas que el JDK verifica. */
class DevAuthTest {

    @TempDir
    Path dir;

    @Test
    void keygenAndTokenProduceAVerifiableEdDsaJws() throws Exception {
        DevAuth.keygen(dir, "dev-1");
        Instant now = Instant.parse("2026-11-22T12:00:00Z");

        String token = DevAuth.token(dir.resolve("dev-1.key"), "dev-1", "est-001", List.of("MAT101", "FIS101"),
                "institucion-dev", "sched-engine", 600, now);

        String[] parts = token.split("\\.");
        assertEquals(3, parts.length);
        JsonNode header = new JsonMapper().readTree(Base64.getUrlDecoder().decode(parts[0]));
        JsonNode claims = new JsonMapper().readTree(Base64.getUrlDecoder().decode(parts[1]));
        assertEquals("EdDSA", header.get("alg").asString());
        assertEquals("dev-1", header.get("kid").asString());
        assertEquals("est-001", claims.get("sub").asString());
        assertEquals(2, claims.get("courses").size());
        assertEquals(now.getEpochSecond() + 600, claims.get("exp").asLong());

        String pem = Files.readString(dir.resolve("dev-1.pem"));
        byte[] der = Base64.getMimeDecoder().decode(
                pem.replace("-----BEGIN PUBLIC KEY-----", "").replace("-----END PUBLIC KEY-----", "").strip());
        Signature verifier = Signature.getInstance("Ed25519");
        verifier.initVerify(KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(der)));
        verifier.update((parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII));
        assertTrue(verifier.verify(Base64.getUrlDecoder().decode(parts[2])));
    }

    @Test
    void keygenDoesNotOverwriteAndValidatesTheKid() throws Exception {
        DevAuth.keygen(dir, "dev-1");

        assertThrows(IllegalArgumentException.class, () -> DevAuth.keygen(dir, "dev-1"));
        assertThrows(IllegalArgumentException.class, () -> DevAuth.keygen(dir, "../malo"));
    }
}
