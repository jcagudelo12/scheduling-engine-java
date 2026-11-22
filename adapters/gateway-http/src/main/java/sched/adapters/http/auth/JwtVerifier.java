package sched.adapters.http.auth;

import static sched.adapters.http.auth.InvalidTokenException.Reason.AUDIENCE;
import static sched.adapters.http.auth.InvalidTokenException.Reason.BAD_SIGNATURE;
import static sched.adapters.http.auth.InvalidTokenException.Reason.EXPIRED;
import static sched.adapters.http.auth.InvalidTokenException.Reason.ISSUER;
import static sched.adapters.http.auth.InvalidTokenException.Reason.LIFETIME;
import static sched.adapters.http.auth.InvalidTokenException.Reason.MALFORMED;
import static sched.adapters.http.auth.InvalidTokenException.Reason.MISSING_CLAIM;
import static sched.adapters.http.auth.InvalidTokenException.Reason.NOT_YET_VALID;
import static sched.adapters.http.auth.InvalidTokenException.Reason.TOO_LARGE;
import static sched.adapters.http.auth.InvalidTokenException.Reason.UNKNOWN_KID;
import static sched.adapters.http.auth.InvalidTokenException.Reason.UNSUPPORTED_ALGORITHM;
import static sched.adapters.http.auth.InvalidTokenException.Reason.UNSUPPORTED_HEADER;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.PublicKey;
import java.security.Signature;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Verifica tokens JWS compactos firmados con EdDSA (Ed25519), solo con el JDK ({@code Signature}
 * "Ed25519") y Jackson para el JSON; no usa librerías JWT (ADR 0004).
 *
 * <p>Orden de las comprobaciones: tamaño, estructura, cabecera ({@code alg} debe ser exactamente
 * {@code "EdDSA"}; se rechazan {@code none}, HS256, RS256 y cualquier otro; {@code crit} no se
 * admite), llave por {@code kid}, <b>firma</b> y solo entonces el payload ({@code exp}, {@code iat}
 * obligatorios; {@code nbf} opcional; {@code iss}, {@code aud}, {@code sub} y {@code courses}).
 * La vida del token ({@code exp - iat}) no puede superar {@link Settings#maxTtl()}.
 *
 * <p>Es seguro entre hilos.
 */
public final class JwtVerifier {

    /** Tope del token completo; uno mayor se rechaza sin decodificarlo. */
    public static final int MAX_TOKEN_BYTES = 8 * 1024;

    /** Fechas más allá del año 9999: absurdas, y evitan desbordes en las cuentas. */
    private static final long MAX_NUMERIC_DATE = 253_402_300_799L;
    private static final int ED25519_SIGNATURE_BYTES = 64;

    /**
     * @param issuer valor exigido en {@code iss}
     * @param audience valor que debe figurar en {@code aud}
     * @param clockSkew tolerancia de reloj para {@code exp}, {@code nbf} e {@code iat}
     * @param maxTtl máxima diferencia permitida entre {@code exp} e {@code iat}
     */
    public record Settings(String issuer, String audience, Duration clockSkew, Duration maxTtl) {
        public Settings {
            if (issuer == null || issuer.isBlank() || audience == null || audience.isBlank()) {
                throw new IllegalArgumentException("El emisor (iss) y la audiencia (aud) del token son obligatorios");
            }
            if (clockSkew == null || clockSkew.isNegative()) {
                throw new IllegalArgumentException("La tolerancia de reloj no puede ser negativa");
            }
            if (maxTtl == null || maxTtl.isZero() || maxTtl.isNegative()) {
                throw new IllegalArgumentException("La vida máxima del token debe ser positiva");
            }
        }
    }

    /** Lo verificado: el estudiante ({@code sub}) y sus cursos habilitados ({@code courses}). */
    public record Claims(String subject, List<String> courses, String kid) {
    }

    private static final JsonMapper JSON = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .build();

    private final Map<String, PublicKey> keys;
    private final Settings settings;
    private final Clock clock;

    public JwtVerifier(Map<String, PublicKey> keys, Settings settings) {
        this(keys, settings, Clock.systemUTC());
    }

    public JwtVerifier(Map<String, PublicKey> keys, Settings settings, Clock clock) {
        if (keys == null || keys.isEmpty()) {
            throw new IllegalArgumentException("Se requiere al menos una llave pública");
        }
        this.keys = Map.copyOf(keys);
        this.settings = settings;
        this.clock = clock;
    }

    public Claims verify(String token) throws InvalidTokenException {
        if (token == null || token.length() > MAX_TOKEN_BYTES) {
            throw new InvalidTokenException(TOO_LARGE, null);
        }
        int firstDot = token.indexOf('.');
        int secondDot = firstDot < 0 ? -1 : token.indexOf('.', firstDot + 1);
        if (firstDot <= 0 || secondDot <= firstDot + 1 || secondDot == token.length() - 1
                || token.indexOf('.', secondDot + 1) >= 0) {
            throw new InvalidTokenException(MALFORMED, null);
        }
        JsonNode header = parseJson(decode(token.substring(0, firstDot), null), null);

        // Cabecera: se confía en ella solo para elegir la llave y rechazar lo no admitido.
        String kid = header.path("kid").isString() ? header.path("kid").asString() : null;
        String safeKid = kid != null && PublicKeys.KID.matcher(kid).matches() ? kid : null;
        if (!header.path("alg").isString() || !"EdDSA".equals(header.path("alg").asString())) {
            throw new InvalidTokenException(UNSUPPORTED_ALGORITHM, safeKid);
        }
        if (header.has("crit") || header.has("jku") || header.has("jwk") || header.has("x5u")) {
            throw new InvalidTokenException(UNSUPPORTED_HEADER, safeKid);
        }
        if (header.has("typ")) {
            String typ = header.get("typ").isString() ? header.get("typ").asString().toLowerCase(Locale.ROOT) : "";
            if (!typ.equals("jwt") && !typ.equals("at+jwt")) {
                throw new InvalidTokenException(UNSUPPORTED_HEADER, safeKid);
            }
        }
        if (header.has("kid") && kid == null) {
            throw new InvalidTokenException(UNKNOWN_KID, null);
        }
        PublicKey key = kid != null ? keys.get(kid) : keys.size() == 1 ? keys.values().iterator().next() : null;
        if (key == null) {
            throw new InvalidTokenException(UNKNOWN_KID, safeKid);
        }

        // La firma se verifica antes de interpretar el payload.
        byte[] signature = decode(token.substring(secondDot + 1), safeKid);
        if (signature.length != ED25519_SIGNATURE_BYTES || !signatureMatches(key,
                token.substring(0, secondDot).getBytes(StandardCharsets.US_ASCII), signature)) {
            throw new InvalidTokenException(BAD_SIGNATURE, safeKid);
        }

        JsonNode payload = parseJson(decode(token.substring(firstDot + 1, secondDot), safeKid), safeKid);
        return checkClaims(payload, safeKid);
    }

    private Claims checkClaims(JsonNode payload, String kid) throws InvalidTokenException {
        long exp = numericDate(payload, "exp", true, kid);
        long iat = numericDate(payload, "iat", true, kid);
        long nbf = numericDate(payload, "nbf", false, kid);
        long now = clock.instant().getEpochSecond();
        long skew = settings.clockSkew().getSeconds();

        if (now - skew >= exp) {
            throw new InvalidTokenException(EXPIRED, kid);
        }
        if (iat > now + skew || (payload.has("nbf") && nbf > now + skew)) {
            throw new InvalidTokenException(NOT_YET_VALID, kid);
        }
        if (exp <= iat || exp - iat > settings.maxTtl().getSeconds()) {
            throw new InvalidTokenException(LIFETIME, kid);
        }
        if (!payload.path("iss").isString() || !settings.issuer().equals(payload.get("iss").asString())) {
            throw new InvalidTokenException(ISSUER, kid);
        }
        if (!audienceMatches(payload.get("aud"))) {
            throw new InvalidTokenException(AUDIENCE, kid);
        }
        if (!payload.path("sub").isString() || payload.get("sub").asString().isBlank()) {
            throw new InvalidTokenException(MISSING_CLAIM, kid);
        }
        JsonNode coursesNode = payload.get("courses");
        if (coursesNode == null || !coursesNode.isArray()) {
            throw new InvalidTokenException(MISSING_CLAIM, kid);
        }
        List<String> courses = new ArrayList<>();
        for (JsonNode course : coursesNode) {
            if (!course.isString()) {
                throw new InvalidTokenException(MISSING_CLAIM, kid);
            }
            courses.add(course.asString());
        }
        return new Claims(payload.get("sub").asString(), List.copyOf(courses), kid);
    }

    private boolean audienceMatches(JsonNode aud) {
        if (aud == null) {
            return false;
        }
        if (aud.isString()) {
            return settings.audience().equals(aud.asString());
        }
        if (aud.isArray()) {
            for (JsonNode item : aud) {
                if (item.isString() && settings.audience().equals(item.asString())) {
                    return true;
                }
            }
        }
        return false;
    }

    private static long numericDate(JsonNode payload, String name, boolean required, String kid)
            throws InvalidTokenException {
        JsonNode node = payload.get(name);
        if (node == null) {
            if (required) {
                throw new InvalidTokenException(MISSING_CLAIM, kid);
            }
            return 0;
        }
        if (!node.isNumber()) {
            throw new InvalidTokenException(MISSING_CLAIM, kid);
        }
        double value = node.doubleValue();
        if (Double.isNaN(value) || value < 0 || value > MAX_NUMERIC_DATE) {
            throw new InvalidTokenException(MISSING_CLAIM, kid);
        }
        return (long) Math.floor(value);
    }

    private static boolean signatureMatches(PublicKey key, byte[] signedInput, byte[] signature) {
        try {
            Signature verifier = Signature.getInstance("Ed25519");
            verifier.initVerify(key);
            verifier.update(signedInput);
            return verifier.verify(signature);
        } catch (GeneralSecurityException e) {
            return false;
        }
    }

    private static byte[] decode(String base64Url, String kid) throws InvalidTokenException {
        try {
            return Base64.getUrlDecoder().decode(base64Url);
        } catch (IllegalArgumentException e) {
            throw new InvalidTokenException(MALFORMED, kid);
        }
    }

    private static JsonNode parseJson(byte[] bytes, String kid) throws InvalidTokenException {
        try {
            JsonNode node = JSON.readTree(bytes);
            if (node == null || !node.isObject()) {
                throw new InvalidTokenException(MALFORMED, kid);
            }
            return node;
        } catch (RuntimeException e) {
            throw new InvalidTokenException(MALFORMED, kid);
        }
    }
}
