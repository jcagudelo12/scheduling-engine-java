package sched.server;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.json.JsonMapper;

/** Llaves Ed25519 y tokens firmados para las pruebas (ADR 0004), solo con el JDK. */
final class TestJwt {

    static final String ISSUER = "institucion-test";
    static final String AUDIENCE = "sched-engine";
    static final String KID = "k1";

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final KeyPair keys;

    TestJwt() {
        try {
            keys = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    java.security.PublicKey publicKey() {
        return keys.getPublic();
    }

    /** Escribe la llave pública como {@code <kid>.pem} (el formato de SCHED_JWT_PUBLIC_KEYS) y devuelve el directorio. */
    Path writePublicKey(Path dir) throws java.io.IOException {
        Files.writeString(dir.resolve(KID + ".pem"), "-----BEGIN PUBLIC KEY-----\n"
                + Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII))
                        .encodeToString(keys.getPublic().getEncoded())
                + "\n-----END PUBLIC KEY-----\n");
        return dir;
    }

    String token(String student, List<String> courses) {
        Instant now = Instant.now();
        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put("iss", ISSUER);
        claims.put("aud", AUDIENCE);
        claims.put("sub", student);
        claims.put("courses", courses);
        claims.put("iat", now.getEpochSecond());
        claims.put("exp", now.plusSeconds(600).getEpochSecond());
        Map<String, Object> header = new LinkedHashMap<>();
        header.put("alg", "EdDSA");
        header.put("typ", "JWT");
        header.put("kid", KID);
        String input = b64(JSON.writeValueAsBytes(header)) + "." + b64(JSON.writeValueAsBytes(claims));
        try {
            Signature signer = Signature.getInstance("Ed25519");
            signer.initSign(keys.getPrivate());
            signer.update(input.getBytes(StandardCharsets.US_ASCII));
            return input + "." + b64(signer.sign());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String b64(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
