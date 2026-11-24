package sched.server;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import sched.adapters.http.auth.JwtVerifier;
import tools.jackson.databind.json.JsonMapper;

/**
 * Tokens de usar y tirar para calentar la verificación EdDSA (ver {@link Warmup}).
 *
 * <p>La llave es <b>efímera</b>: se genera en memoria, firma todos los tokens al crear el conjunto y
 * después nadie la referencia (queda para el recolector). Solo la conoce un {@link JwtVerifier}
 * <b>distinto</b> del de producción, con su propio emisor y audiencia; la llave pública no se
 * registra en el verificador real, ni se escribe en disco, ni se registra en los logs. Un token
 * firmado con ella no es aceptado por el verificador de producción.
 *
 * @param verifier verificador exclusivo del calentamiento
 * @param tokens un token por cada lista de cursos recibida, en el mismo orden
 */
record WarmupTokens(JwtVerifier verifier, List<String> tokens) {

    private static final String KID = "warmup";
    private static final String ISSUER = "sched-warmup";
    private static final String AUDIENCE = "sched-warmup";
    private static final Duration TTL = Duration.ofMinutes(10);

    static WarmupTokens issue(JsonMapper json, List<String> subjects, List<List<String>> courses) {
        try {
            KeyPair pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
            var settings = new JwtVerifier.Settings(ISSUER, AUDIENCE, Duration.ofSeconds(30), Duration.ofMinutes(15));
            var verifier = new JwtVerifier(Map.of(KID, pair.getPublic()), settings);
            Map<String, Object> header = new LinkedHashMap<>();
            header.put("alg", "EdDSA");
            header.put("typ", "JWT");
            header.put("kid", KID);
            String encodedHeader = b64(json.writeValueAsBytes(header));
            long now = Instant.now().getEpochSecond();
            var tokens = new java.util.ArrayList<String>(subjects.size());
            for (int i = 0; i < subjects.size(); i++) {
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("iss", ISSUER);
                payload.put("aud", AUDIENCE);
                payload.put("sub", subjects.get(i));
                payload.put("courses", courses.get(i));
                payload.put("iat", now);
                payload.put("exp", now + TTL.getSeconds());
                String input = encodedHeader + "." + b64(json.writeValueAsBytes(payload));
                Signature signer = Signature.getInstance("Ed25519");
                signer.initSign(pair.getPrivate());
                signer.update(input.getBytes(StandardCharsets.US_ASCII));
                tokens.add(input + "." + b64(signer.sign()));
            }
            return new WarmupTokens(verifier, List.copyOf(tokens));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("no se pudo crear la llave efímera del calentamiento", e);
        }
    }

    private static String b64(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
