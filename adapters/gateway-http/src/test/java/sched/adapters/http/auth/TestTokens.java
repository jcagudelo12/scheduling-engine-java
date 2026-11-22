package sched.adapters.http.auth;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.Signature;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.json.JsonMapper;

/** Genera llaves Ed25519 y firma tokens para las pruebas, con el JDK. */
public final class TestTokens {

    public static final String ISSUER = "institucion-test";
    public static final String AUDIENCE = "sched-engine";

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private TestTokens() {
    }

    public static KeyPair newKeyPair() {
        try {
            return KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** Payload válido a las {@code now}; las pruebas lo alteran con {@code put}/{@code remove}. */
    public static Map<String, Object> claims(Instant now, String student, List<String> courses) {
        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put("iss", ISSUER);
        claims.put("aud", AUDIENCE);
        claims.put("sub", student);
        claims.put("courses", courses);
        claims.put("iat", now.getEpochSecond());
        claims.put("exp", now.plusSeconds(600).getEpochSecond());
        return claims;
    }

    public static Map<String, Object> header(String alg, String kid) {
        Map<String, Object> header = new LinkedHashMap<>();
        header.put("alg", alg);
        header.put("typ", "JWT");
        if (kid != null) {
            header.put("kid", kid);
        }
        return header;
    }

    /** Firma con Ed25519 la cabecera y el payload dados, tal cual. */
    public static String sign(PrivateKey key, Map<String, Object> header, Map<String, Object> payload) {
        String input = b64(JSON.writeValueAsBytes(header)) + "." + b64(JSON.writeValueAsBytes(payload));
        try {
            Signature signer = Signature.getInstance("Ed25519");
            signer.initSign(key);
            signer.update(input.getBytes(StandardCharsets.US_ASCII));
            return input + "." + b64(signer.sign());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public static String sign(PrivateKey key, String kid, Map<String, Object> payload) {
        return sign(key, header("EdDSA", kid), payload);
    }

    /** Un token con una firma de relleno y el {@code alg} indicado (para probar que se rechaza). */
    public static String unsigned(Map<String, Object> header, Map<String, Object> payload, byte[] signature) {
        return b64(JSON.writeValueAsBytes(header)) + "." + b64(JSON.writeValueAsBytes(payload)) + "."
                + b64(signature);
    }

    public static String b64(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public static String pem(java.security.PublicKey key) {
        return "-----BEGIN PUBLIC KEY-----\n"
                + Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII)).encodeToString(key.getEncoded())
                + "\n-----END PUBLIC KEY-----\n";
    }
}
