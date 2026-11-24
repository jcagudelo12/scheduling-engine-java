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
import java.security.PublicKey;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.bouncycastle.math.ec.rfc8032.Ed25519;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Verifica tokens JWS compactos firmados con EdDSA (Ed25519). Usa Jackson para el JSON y la
 * implementación de Ed25519 de BouncyCastle (solo {@code Ed25519}, sin el proveedor JCA) para la
 * firma, unas 9 veces más rápida que la del JDK; no usa librerías JWT (ADR 0004).
 *
 * <p>Orden de las comprobaciones, de lo más barato a lo más caro: tamaño, estructura, cabecera
 * ({@code alg} debe ser exactamente {@code "EdDSA"}; se rechazan {@code none}, HS256, RS256 y
 * cualquier otro; {@code crit} no se admite), llave por {@code kid}, forma de la firma, payload
 * ({@code exp}, {@code iat} obligatorios; {@code nbf} opcional; {@code iss}, {@code aud},
 * {@code sub} y {@code courses}) y, por último, la <b>firma</b>. Así un token vencido, de otra
 * audiencia o mal formado se rechaza sin gastar una verificación criptográfica. El payload se lee
 * antes de comprobar la firma solo para rechazar; <b>nada se acepta ni se devuelve sin la firma
 * verificada</b>. La vida del token ({@code exp - iat}) no puede superar {@link Settings#maxTtl()}.
 *
 * <p>Las llaves se decodifican una sola vez al construir el verificador (el punto de la curva queda
 * precalculado); por solicitud no se crea ningún objeto de {@code Signature} ni se busca un proveedor.
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

    private static final byte[] ED25519_X509_PREFIX = {
        0x30, 0x2a, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x70, 0x03, 0x21, 0x00};

    private final Map<String, Ed25519.PublicPoint> keys;
    private final Settings settings;
    private final Clock clock;

    public JwtVerifier(Map<String, PublicKey> keys, Settings settings) {
        this(keys, settings, Clock.systemUTC());
    }

    public JwtVerifier(Map<String, PublicKey> keys, Settings settings, Clock clock) {
        if (keys == null || keys.isEmpty()) {
            throw new IllegalArgumentException("Se requiere al menos una llave pública");
        }
        Map<String, Ed25519.PublicPoint> points = new LinkedHashMap<>();
        keys.forEach((kid, key) -> points.put(kid, publicPoint(key)));
        this.keys = Map.copyOf(points);
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
        Ed25519.PublicPoint key = kid != null ? keys.get(kid) : keys.size() == 1 ? keys.values().iterator().next() : null;
        if (key == null) {
            throw new InvalidTokenException(UNKNOWN_KID, safeKid);
        }

        // Rechazos baratos primero: la forma de la firma y los claims se comprueban antes de gastar la
        // verificación criptográfica. Ningún claim se usa ni se devuelve sin la firma verificada.
        byte[] signature = decode(token.substring(secondDot + 1), safeKid);
        if (signature.length != ED25519_SIGNATURE_BYTES) {
            throw new InvalidTokenException(BAD_SIGNATURE, safeKid);
        }
        JsonNode payload = parseJson(decode(token.substring(firstDot + 1, secondDot), safeKid), safeKid);
        Claims claims = checkClaims(payload, safeKid);

        byte[] signedInput = token.substring(0, secondDot).getBytes(StandardCharsets.US_ASCII);
        if (!signatureMatches(key, signedInput, signature)) {
            throw new InvalidTokenException(BAD_SIGNATURE, safeKid);
        }
        return claims;
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

    private static boolean signatureMatches(Ed25519.PublicPoint key, byte[] signedInput, byte[] signature) {
        try {
            return Ed25519.verify(signature, 0, key, signedInput, 0, signedInput.length);
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** Extrae el punto de la curva de una llave pública Ed25519 (X.509); se hace una vez por llave. */
    private static Ed25519.PublicPoint publicPoint(PublicKey key) {
        byte[] der = key.getEncoded();
        if (der == null || der.length != ED25519_X509_PREFIX.length + Ed25519.PUBLIC_KEY_SIZE
                || !Arrays.equals(der, 0, ED25519_X509_PREFIX.length, ED25519_X509_PREFIX, 0, ED25519_X509_PREFIX.length)) {
            throw new IllegalArgumentException("La llave pública no es Ed25519 en formato X.509");
        }
        Ed25519.PublicPoint point = Ed25519.validatePublicKeyFullExport(der, ED25519_X509_PREFIX.length);
        if (point == null) {
            throw new IllegalArgumentException("La llave pública Ed25519 no es un punto válido de la curva");
        }
        return point;
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
