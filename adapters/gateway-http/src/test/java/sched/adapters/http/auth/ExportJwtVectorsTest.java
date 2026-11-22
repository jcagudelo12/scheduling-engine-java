package sched.adapters.http.auth;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.PublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import org.bouncycastle.math.ec.rfc8032.Ed25519;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Exporta vectores de verificación del token para la versión en Rust: tokens firmados con llaves de
 * prueba fijas (firma Ed25519 determinista) y, para cada uno, lo que decide {@link JwtVerifier} con un
 * reloj fijo: aceptado (con los claims) o rechazado (con la razón interna y el {@code kid}).
 * También exporta casos de {@link PublicKeys#parsePem}.
 *
 * <p>No toca código de producción: el verificador ya recibe el reloj. Las semillas de las llaves son
 * PÚBLICAS y solo sirven para estas pruebas (no son secretos).
 *
 * <p>Siempre escribe {@code target/jwt-vectors.json}; con {@code -Dsched.export.jwt-vectors=<ruta>}
 * también lo copia a la ruta dada (el fixture de {@code rust/gateway_http/tests/fixtures}).
 */
class ExportJwtVectorsTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Instant NOW = Instant.parse("2026-11-22T12:00:00Z");
    private static final long NOW_S = NOW.getEpochSecond();
    private static final String ISS = "institucion-test";
    private static final String AUD = "sched-engine";
    private static final byte[] SEED1 = HexFormat.of().parseHex("42".repeat(32));
    private static final byte[] SEED2 = HexFormat.of().parseHex("43".repeat(32));
    private static final byte[] SEED_OTHER = HexFormat.of().parseHex("44".repeat(32));
    private static final byte[] DER_PREFIX = {0x30, 0x2a, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x70, 0x03, 0x21, 0x00};
    private static final BigInteger L = BigInteger.ONE.shiftLeft(252)
            .add(new BigInteger("27742317777372353535851937790883648493"));

    private static byte[] publicOf(byte[] seed) {
        byte[] pk = new byte[Ed25519.PUBLIC_KEY_SIZE];
        Ed25519.generatePublicKey(seed, 0, pk, 0);
        return pk;
    }

    private static byte[] der(byte[] pk) {
        byte[] der = new byte[DER_PREFIX.length + pk.length];
        System.arraycopy(DER_PREFIX, 0, der, 0, DER_PREFIX.length);
        System.arraycopy(pk, 0, der, DER_PREFIX.length, pk.length);
        return der;
    }

    private static PublicKey key(byte[] seed) throws Exception {
        return java.security.KeyFactory.getInstance("Ed25519")
                .generatePublic(new X509EncodedKeySpec(der(publicOf(seed))));
    }

    private static String b64(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static byte[] sign(byte[] seed, String signingInput) {
        byte[] message = signingInput.getBytes(StandardCharsets.US_ASCII);
        byte[] sig = new byte[Ed25519.SIGNATURE_SIZE];
        Ed25519.sign(seed, 0, publicOf(seed), 0, null, message, 0, message.length, sig, 0);
        return sig;
    }

    private static byte[] utf8(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    /** Token firmado con la llave 1 sobre estos bytes exactos de cabecera y payload. */
    private static String signedBytes(byte[] header, byte[] payload) {
        String input = b64(header) + "." + b64(payload);
        return input + "." + b64(sign(SEED1, input));
    }

    private static String signed(String header, String payload) {
        return signedBytes(utf8(header), utf8(payload));
    }

    private static String signedWith(byte[] seed, String header, String payload) {
        String input = b64(utf8(header)) + "." + b64(utf8(payload));
        return input + "." + b64(sign(seed, input));
    }

    private static final String H = "{\"alg\":\"EdDSA\",\"typ\":\"JWT\",\"kid\":\"k1\"}";

    private static String claims(String... overrides) {
        // Claims válidos, en este orden; cada override reemplaza la clave o, si es "-clave", la quita.
        Map<String, String> base = new java.util.LinkedHashMap<>();
        base.put("iss", "\"" + ISS + "\"");
        base.put("aud", "\"" + AUD + "\"");
        base.put("sub", "\"est-1\"");
        base.put("courses", "[\"MAT\",\"FIS\"]");
        base.put("iat", Long.toString(NOW_S));
        base.put("exp", Long.toString(NOW_S + 600));
        for (String override : overrides) {
            if (override.startsWith("-")) {
                base.remove(override.substring(1));
            } else {
                int eq = override.indexOf('=');
                base.put(override.substring(0, eq), override.substring(eq + 1));
            }
        }
        StringBuilder sb = new StringBuilder("{");
        base.forEach((k, v) -> sb.append(sb.length() > 1 ? "," : "").append('"').append(k).append("\":").append(v));
        return sb.append('}').toString();
    }

    private static String tokenWith(String... overrides) {
        return signed(H, claims(overrides));
    }

    private final List<Object[]> vectors = new ArrayList<>();

    private void add(String name, String verifier, String token) {
        vectors.add(new Object[] {name, verifier, token});
    }

    private void one(String name, String token) {
        add(name, "one", token);
    }

    @Test
    void exportVectors() throws Exception {
        PublicKey k1 = key(SEED1);
        PublicKey k2 = key(SEED2);
        JwtVerifier.Settings settings = new JwtVerifier.Settings(ISS, AUD, Duration.ofSeconds(30), Duration.ofSeconds(900));
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        Map<String, JwtVerifier> verifiers = Map.of(
                "one", new JwtVerifier(Map.of("k1", k1), settings, clock),
                "two", new JwtVerifier(Map.of("k1", k1, "k2", k2), settings, clock));

        buildVectors();

        ObjectNode root = JSON.createObjectNode();
        root.put("_comment", "Generado por sched.adapters.http.auth.ExportJwtVectorsTest (repositorio scheduling-engine-java); "
                + "no editar a mano. Las llaves son de prueba (semillas fijas y publicas), no son secretos. "
                + "now fijo; 'one' = solo la llave k1; 'two' = k1 y k2.");
        root.put("now_epoch", NOW_S);
        root.put("issuer", ISS);
        root.put("audience", AUD);
        root.put("clock_skew_s", 30);
        root.put("max_ttl_s", 900);
        ObjectNode keys = root.putObject("keys");
        keys.put("k1", Base64.getEncoder().encodeToString(der(publicOf(SEED1))));
        keys.put("k2", Base64.getEncoder().encodeToString(der(publicOf(SEED2))));
        ArrayNode cases = root.putArray("vectors");
        int accepted = 0;
        java.util.Set<String> reasons = new java.util.TreeSet<>();
        for (Object[] v : vectors) {
            String name = (String) v[0];
            String verifier = (String) v[1];
            String token = (String) v[2];
            ObjectNode node = cases.addObject();
            node.put("name", name);
            node.put("verifier", verifier);
            node.put("token", token);
            try {
                JwtVerifier.Claims claims = verifiers.get(verifier).verify(token);
                node.put("result", "ok");
                node.put("subject", noLoneSurrogates(claims.subject()));
                ArrayNode courses = node.putArray("courses");
                claims.courses().forEach(c -> courses.add(noLoneSurrogates(c)));
                node.put("kid", claims.kid());
                accepted++;
            } catch (InvalidTokenException e) {
                node.put("result", "error");
                node.put("reason", e.reason().code());
                if (e.kid() == null) {
                    node.putNull("kid");
                } else {
                    node.put("kid", e.kid());
                }
                reasons.add(e.reason().code());
            } catch (RuntimeException e) {
                // Hallazgo: un entero fuera del rango de un double en exp/iat/nbf hace que Jackson lance una
                // excepción no controlada (JsonNodeException) en vez de InvalidTokenException; el filtro no la
                // captura y la respuesta sería 500. Falla cerrado, pero no es el 401 documentado. La versión en
                // Rust responde con el rechazo equivalente (claim_ausente_o_invalido).
                node.put("result", "java_unchecked_exception");
                node.put("exception", e.getClass().getSimpleName());
            }
        }
        ArrayNode pems = root.putArray("pems");
        for (Object[] p : pemCases()) {
            ObjectNode node = pems.addObject();
            node.put("name", (String) p[0]);
            node.put("pem", (String) p[1]);
            try {
                PublicKey key = PublicKeys.parsePem((String) p[1]);
                node.put("result", "ok");
                node.put("der_b64", Base64.getEncoder().encodeToString(key.getEncoded()));
                try {
                    new JwtVerifier(Map.of("k", key), settings, clock);
                    node.put("verifier_accepts", true);
                } catch (IllegalArgumentException e) {
                    node.put("verifier_accepts", false);
                }
            } catch (IllegalArgumentException e) {
                node.put("result", "error");
            }
        }

        String json = JSON.writerWithDefaultPrettyPrinter().writeValueAsString(root);
        Path target = Path.of("target", "jwt-vectors.json");
        Files.createDirectories(target.getParent());
        Files.writeString(target, json + "\n", StandardCharsets.UTF_8);
        String export = System.getProperty("sched.export.jwt-vectors");
        if (export != null && !export.isBlank()) {
            Files.writeString(Path.of(export), json + "\n", StandardCharsets.UTF_8);
        }
        System.out.println("jwt-vectors: " + vectors.size() + " vectores, " + accepted + " aceptados, razones: " + reasons);
        assertTrue(vectors.size() > 150);
        assertTrue(reasons.size() == 12, "faltan razones: " + reasons);
    }

    private static List<Object[]> pemCases() throws Exception {
        String body = Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII)).encodeToString(der(publicOf(SEED1)));
        String b = "-----BEGIN PUBLIC KEY-----\n" + body + "\n-----END PUBLIC KEY-----\n";
        List<Object[]> out = new ArrayList<>();
        out.add(new Object[] {"valid", b});
        out.add(new Object[] {"valid without trailing newline", b.trim()});
        out.add(new Object[] {"valid with CRLF", b.replace("\n", "\r\n")});
        out.add(new Object[] {"text before and after", "comentario\n" + b + "\nmas texto"});
        out.add(new Object[] {"spaces in body", b.replace(body, "  " + body.replace("\n", " \n ") + "  ")});
        out.add(new Object[] {"single line body", "-----BEGIN PUBLIC KEY-----" + body.replace("\n", "") + "-----END PUBLIC KEY-----"});
        out.add(new Object[] {"private key", "-----BEGIN PRIVATE KEY-----\nAAAA\n-----END PRIVATE KEY-----\n"});
        out.add(new Object[] {"private key text after public", b + "-----BEGIN PRIVATE KEY-----\nAAAA\n-----END PRIVATE KEY-----\n"});
        out.add(new Object[] {"junk body", "-----BEGIN PUBLIC KEY-----\nAAAA\n-----END PUBLIC KEY-----\n"});
        out.add(new Object[] {"not base64", "-----BEGIN PUBLIC KEY-----\n!!!!\n-----END PUBLIC KEY-----\n"});
        out.add(new Object[] {"no markers", body});
        out.add(new Object[] {"end before begin", "-----END PUBLIC KEY-----\n" + body + "\n-----BEGIN PUBLIC KEY-----\n"});
        out.add(new Object[] {"empty", ""});
        out.add(new Object[] {"two keys (the first is used)", b + b.replace(body, Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(der(publicOf(SEED2))))});
        out.add(new Object[] {"url-safe alphabet in pem", "-----BEGIN PUBLIC KEY-----\n" + body.replace('+', '-').replace('/', '_') + "\n-----END PUBLIC KEY-----\n"});
        byte[] shortDer = Arrays.copyOf(der(publicOf(SEED1)), 43);
        out.add(new Object[] {"truncated der", "-----BEGIN PUBLIC KEY-----\n" + Base64.getEncoder().encodeToString(shortDer) + "\n-----END PUBLIC KEY-----\n"});
        byte[] extra = Arrays.copyOf(der(publicOf(SEED1)), 45);
        out.add(new Object[] {"der with trailing bytes", "-----BEGIN PUBLIC KEY-----\n" + Base64.getEncoder().encodeToString(extra) + "\n-----END PUBLIC KEY-----\n"});
        byte[] x25519 = der(publicOf(SEED1));
        x25519[8] = 0x6e; // OID 1.3.101.110 (X25519) en lugar de 1.3.101.112
        out.add(new Object[] {"x25519 oid", "-----BEGIN PUBLIC KEY-----\n" + Base64.getEncoder().encodeToString(x25519) + "\n-----END PUBLIC KEY-----\n"});
        byte[] notOnCurve = der(publicOf(SEED1));
        // y = 2 no es un punto de la curva (no hay x que cumpla la ecuación)
        Arrays.fill(notOnCurve, DER_PREFIX.length, notOnCurve.length, (byte) 0);
        notOnCurve[DER_PREFIX.length] = 2;
        out.add(new Object[] {"point not on the curve", "-----BEGIN PUBLIC KEY-----\n" + Base64.getEncoder().encodeToString(notOnCurve) + "\n-----END PUBLIC KEY-----\n"});
        byte[] identity = der(publicOf(SEED1));
        Arrays.fill(identity, DER_PREFIX.length, identity.length, (byte) 0);
        identity[DER_PREFIX.length] = 1;
        out.add(new Object[] {"identity point", "-----BEGIN PUBLIC KEY-----\n" + Base64.getEncoder().encodeToString(identity) + "\n-----END PUBLIC KEY-----\n"});
        return out;
    }

    private void buildVectors() {
        String valid = tokenWith();
        one("valid", valid);
        add("valid with the two-key verifier", "two", valid);
        add("valid k2 with the two-key verifier", "two", signedWith(SEED2, "{\"alg\":\"EdDSA\",\"kid\":\"k2\"}", claims()));
        one("valid k2 on the one-key verifier", signedWith(SEED2, "{\"alg\":\"EdDSA\",\"kid\":\"k2\"}", claims()));
        one("no kid, one key", signed("{\"alg\":\"EdDSA\"}", claims()));
        add("no kid, two keys", "two", signed("{\"alg\":\"EdDSA\"}", claims()));
        one("no typ", signed("{\"alg\":\"EdDSA\",\"kid\":\"k1\"}", claims()));
        one("typ at+jwt", signed("{\"alg\":\"EdDSA\",\"typ\":\"at+jwt\",\"kid\":\"k1\"}", claims()));
        one("typ jWt", signed("{\"alg\":\"EdDSA\",\"typ\":\"jWt\",\"kid\":\"k1\"}", claims()));
        one("typ AT+JWT", signed("{\"alg\":\"EdDSA\",\"typ\":\"AT+JWT\",\"kid\":\"k1\"}", claims()));
        one("typ JOSE", signed("{\"alg\":\"EdDSA\",\"typ\":\"JOSE\",\"kid\":\"k1\"}", claims()));
        one("typ number", signed("{\"alg\":\"EdDSA\",\"typ\":5,\"kid\":\"k1\"}", claims()));
        one("typ null", signed("{\"alg\":\"EdDSA\",\"typ\":null,\"kid\":\"k1\"}", claims()));
        one("typ empty", signed("{\"alg\":\"EdDSA\",\"typ\":\"\",\"kid\":\"k1\"}", claims()));
        one("typ with kelvin sign", signed("{\"alg\":\"EdDSA\",\"typ\":\"\\u212a\",\"kid\":\"k1\"}", claims()));
        one("typ jwt with trailing space", signed("{\"alg\":\"EdDSA\",\"typ\":\"jwt \",\"kid\":\"k1\"}", claims()));
        // alg
        for (String alg : new String[] {"\"none\"", "\"None\"", "\"HS256\"", "\"RS256\"", "\"ES256\"", "\"EdDSA \"", "\"eddsa\"",
                "\"Ed25519\"", "\"\"", "5", "null", "true", "[\"EdDSA\"]", "{\"a\":1}"}) {
            one("alg " + alg, signed("{\"alg\":" + alg + ",\"kid\":\"k1\"}", claims()));
        }
        one("alg missing", signed("{\"kid\":\"k1\"}", claims()));
        one("alg with escapes (EdDS\\u0041)", signed("{\"alg\":\"EdDS\\u0041\",\"kid\":\"k1\"}", claims()));
        // cabeceras no admitidas, aunque sean null
        for (String extra : new String[] {"\"crit\":[\"exp\"]", "\"crit\":null", "\"crit\":[]", "\"jku\":\"http://x\"", "\"jku\":null",
                "\"jwk\":{}", "\"jwk\":null", "\"x5u\":\"u\"", "\"x5u\":null", "\"x5c\":[\"a\"]", "\"cty\":\"JWT\"", "\"zip\":\"DEF\"", "\"b64\":false"}) {
            one("header extra " + extra, signed("{\"alg\":\"EdDSA\",\"kid\":\"k1\"," + extra + "}", claims()));
        }
        one("alg none and crit (alg wins)", signed("{\"alg\":\"none\",\"crit\":[\"a\"],\"kid\":\"k1\"}", claims()));
        one("crit and bad typ (header wins over typ)", signed("{\"alg\":\"EdDSA\",\"crit\":[\"a\"],\"typ\":\"x\",\"kid\":\"k1\"}", claims()));
        one("bad typ and unknown kid (typ first)", signed("{\"alg\":\"EdDSA\",\"typ\":\"x\",\"kid\":\"zz\"}", claims()));
        one("bad alg and unknown kid (alg first)", signed("{\"alg\":\"HS256\",\"kid\":\"zz\"}", claims()));
        one("bad alg with invalid kid chars", signed("{\"alg\":\"HS256\",\"kid\":\"a b\"}", claims()));
        // kid
        one("kid unknown", signed("{\"alg\":\"EdDSA\",\"kid\":\"zz\"}", claims()));
        one("kid number", signed("{\"alg\":\"EdDSA\",\"kid\":1}", claims()));
        one("kid null", signed("{\"alg\":\"EdDSA\",\"kid\":null}", claims()));
        one("kid with spaces", signed("{\"alg\":\"EdDSA\",\"kid\":\"k 1\"}", claims()));
        one("kid empty", signed("{\"alg\":\"EdDSA\",\"kid\":\"\"}", claims()));
        one("kid 65 chars", signed("{\"alg\":\"EdDSA\",\"kid\":\"" + "k".repeat(65) + "\"}", claims()));
        one("kid uppercase K1", signed("{\"alg\":\"EdDSA\",\"kid\":\"K1\"}", claims()));
        one("kid array", signed("{\"alg\":\"EdDSA\",\"kid\":[\"k1\"]}", claims()));
        // estructura
        for (String t : new String[] {"", "a", "a.b", "a.b.c.d", "..", "a..c", ".b.c", "a.b.", "a.b.c.", "...", "a.", ".", "x.y.z"}) {
            one("structure [" + t + "]", t);
        }
        one("structure: header only valid then garbage", b64(utf8(H)) + ".!!!.AAAA");
        // tamaño
        String small = tokenWith();
        String t8192 = exactLength(8192);
        one("size 8192 exact", t8192);
        one("size 8193", exactLength(8193));
        one("size 9000 garbage", "a".repeat(9000));
        one("size 8192 garbage", "a".repeat(8192));
        // base64url
        String[] parts = small.split("\\.");
        one("header with padding", parts[0] + "==".repeat(0) + "." + parts[1] + "." + parts[2]);
        for (int i = 0; i < 3; i++) {
            String seg = parts[i];
            int pads = (4 - seg.length() % 4) % 4;
            String padded = seg + "=".repeat(pads);
            String[] copy = parts.clone();
            copy[i] = padded;
            one("segment " + i + " with correct padding (" + pads + ")", String.join(".", copy));
            copy[i] = seg + "=".repeat(pads == 0 ? 1 : pads + 1);
            one("segment " + i + " with wrong padding", String.join(".", copy));
            copy[i] = seg.substring(0, Math.max(1, seg.length() - 1)) + "+";
            one("segment " + i + " with plus", String.join(".", copy));
            copy[i] = seg.substring(0, Math.max(1, seg.length() - 1)) + "/";
            one("segment " + i + " with slash", String.join(".", copy));
            copy[i] = seg + "\n";
            one("segment " + i + " with newline", String.join(".", copy));
            copy[i] = seg.substring(0, 4) + " " + seg.substring(4);
            one("segment " + i + " with space inside", String.join(".", copy));
            copy[i] = seg.substring(0, 4) + "\u00e9" + seg.substring(4);
            one("segment " + i + " with non ascii", String.join(".", copy));
            copy[i] = seg + "A";
            one("segment " + i + " with one extra char", String.join(".", copy));
            copy[i] = seg.substring(0, seg.length() - 1);
            one("segment " + i + " minus one char", String.join(".", copy));
        }
        // bits de relleno distintos de cero en el último carácter de la cabecera (Java los acepta)
        one("header last char with nonzero trailing bits", lastCharVariant(parts));
        // JSON de la cabecera
        for (String header : new String[] {"[]", "\"x\"", "5", "null", "true", "", " ", "{", "{\"alg\":\"EdDSA\"", "{\"alg\":\"EdDSA\",}",
                "{'alg':'EdDSA'}", "{alg:\"EdDSA\"}", "{\"alg\":\"EdDSA\"}xx", "{\"alg\":\"EdDSA\"}{}", "{\"alg\":\"EdDSA\"} ",
                "{\"alg\":\"EdDSA\"}\n", "\t{\"alg\":\"EdDSA\"}", "{\"alg\":\"EdDSA\",\"alg\":\"EdDSA\"}", "{\"alg\":\"EdDSA\",\"kid\":\"k1\",\"kid\":\"k1\"}",
                "{\"alg\":\"EdDSA\",\"x\":{\"a\":1,\"a\":2}}", "{\"alg\":\"EdDSA\",\"x\":[{\"a\":1,\"a\":2}]}", "{\"alg\":\"EdDSA\",\"al\\u0067\":\"EdDSA\"}",
                "{\"alg\":\"EdDSA\",\"kid\":\"k1\"/*c*/}", "{\"alg\":\"EdDSA\",\"x\":NaN}", "{\"alg\":\"EdDSA\",\"x\":01}", "{\"alg\":\"EdDSA\",\"x\":1.}",
                "{\"alg\":\"EdDSA\",\"x\":-}", "{\"alg\":\"EdDSA\",\"x\":.5}", "{\"alg\":\"EdDSA\",\"x\":+1}", "{\"alg\":\"EdDSA\",\"x\":1e5}",
                "{\"alg\":\"EdDSA\",\"x\":-0}", "{\"alg\":\"EdDSA\",\"x\":\"\\ud800\"}", "{\"alg\":\"EdDSA\",\"x\":\"\\q\"}",
                "{\"alg\":\"EdDSA\",\"x\":\"a\tb\"}", "{\"alg\":\"EdDSA\",\"x\":\"\\u00zz\"}", "{\"alg\":\"EdDSA\",\"x\":tru}",
                "{\"alg\":\"EdDSA\",\"x\":[1,2,]}", "{\"alg\":\"EdDSA\",\"x\":nul}", "{\"alg\":\"EdDSA\",\"kid\":\"k1\",\"x\":1} garbage",
                "{\"alg\":\"EdDSA\",\"kid\":\"k1\"}\u0000"}) {
            one("header json [" + header.replace("\n", "\\n").replace("\t", "\\t") + "]", signed(header, claims()));
        }
        one("header with BOM", signedBytes(concat(new byte[] {(byte) 0xef, (byte) 0xbb, (byte) 0xbf}, utf8(H)), utf8(claims())));
        one("header invalid utf-8", signedBytes(concat(utf8("{\"alg\":\"EdDSA\",\"kid\":\"k1\",\"x\":\""), new byte[] {(byte) 0xff}, utf8("\"}")), utf8(claims())));
        one("header utf-16le", signedBytes(H.getBytes(StandardCharsets.UTF_16LE), utf8(claims())));
        one("header utf-16be", signedBytes(H.getBytes(StandardCharsets.UTF_16BE), utf8(claims())));
        one("header utf-32be", signedBytes(H.getBytes(java.nio.charset.Charset.forName("UTF-32BE")), utf8(claims())));
        one("header nesting 400", signed("{\"alg\":\"EdDSA\",\"kid\":\"k1\",\"x\":" + "[".repeat(400) + "]".repeat(400) + "}", claims()));
        one("header nesting 600", signed("{\"alg\":\"EdDSA\",\"kid\":\"k1\",\"x\":" + "[".repeat(600) + "]".repeat(600) + "}", claims()));
        one("header long number 1001 digits", signed("{\"alg\":\"EdDSA\",\"kid\":\"k1\",\"x\":" + "1".repeat(1001) + "}", claims()));
        one("header long number 999 digits", signed("{\"alg\":\"EdDSA\",\"kid\":\"k1\",\"x\":" + "1".repeat(999) + "}", claims()));
        // payload
        one("payload extra claims", signed(H, claims("extra={\"a\":[1,2,3]}", "nbf=null".replace("nbf=null", "role=\"admin\""))));
        one("payload aud array containing", tokenWith("aud=[\"otra\",\"" + AUD + "\"]"));
        one("payload aud array not containing", tokenWith("aud=[\"otra\"]"));
        one("payload aud array with numbers then match", tokenWith("aud=[1,null,\"" + AUD + "\"]"));
        one("payload aud empty array", tokenWith("aud=[]"));
        one("payload aud number", tokenWith("aud=5"));
        one("payload aud null", tokenWith("aud=null"));
        one("payload aud object", tokenWith("aud={}"));
        one("payload aud missing", tokenWith("-aud"));
        one("payload aud different case", tokenWith("aud=\"SCHED-ENGINE\""));
        one("payload iss other", tokenWith("iss=\"otra\""));
        one("payload iss missing", tokenWith("-iss"));
        one("payload iss number", tokenWith("iss=5"));
        one("payload iss null", tokenWith("iss=null"));
        one("payload iss array", tokenWith("iss=[\"" + ISS + "\"]"));
        one("payload iss with escapes", tokenWith("iss=\"institucion-tes\\u0074\""));
        one("payload sub missing", tokenWith("-sub"));
        one("payload sub null", tokenWith("sub=null"));
        one("payload sub number", tokenWith("sub=5"));
        one("payload sub empty", tokenWith("sub=\"\""));
        one("payload sub blank", tokenWith("sub=\"   \""));
        one("payload sub nbsp", tokenWith("sub=\"\\u00a0\""));
        one("payload sub figure space", tokenWith("sub=\"\\u2007\""));
        one("payload sub narrow nbsp", tokenWith("sub=\"\\u202f\""));
        one("payload sub unit separator", tokenWith("sub=\"\\u001f\""));
        one("payload sub file separator", tokenWith("sub=\"\\u001c\""));
        one("payload sub line separator", tokenWith("sub=\"\\u2028\""));
        one("payload sub ideographic space", tokenWith("sub=\"\\u3000\""));
        one("payload sub NEL", tokenWith("sub=\"\\u0085\""));
        one("payload sub vertical tab", tokenWith("sub=\"\\u000b\""));
        one("payload sub zero width space", tokenWith("sub=\"\\u200b\""));
        one("payload sub mongolian vowel separator", tokenWith("sub=\"\\u180e\""));
        one("payload sub ogham space", tokenWith("sub=\"\\u1680\""));
        one("payload sub non-ascii", tokenWith("sub=\"\\u00f1and\\u00fa\""));
        one("payload sub emoji", tokenWith("sub=\"\\ud83d\\ude00\""));
        one("payload sub lone surrogate", tokenWith("sub=\"\\ud800\""));
        one("payload courses missing", tokenWith("-courses"));
        one("payload courses null", tokenWith("courses=null"));
        one("payload courses string", tokenWith("courses=\"MAT\""));
        one("payload courses object", tokenWith("courses={}"));
        one("payload courses numbers", tokenWith("courses=[1,2]"));
        one("payload courses with null", tokenWith("courses=[\"MAT\",null]"));
        one("payload courses nested", tokenWith("courses=[[\"MAT\"]]"));
        one("payload courses empty", tokenWith("courses=[]"));
        one("payload courses repeated", tokenWith("courses=[\"MAT\",\"MAT\"]"));
        one("payload courses with spaces", tokenWith("courses=[\"M A T\"]"));
        one("payload courses non-ascii", tokenWith("courses=[\"MA\\u00d1\"]"));
        one("payload courses 13", tokenWith("courses=[" + "\"A\",".repeat(12) + "\"A\"]"));
        // fechas
        one("exp missing", tokenWith("-exp"));
        one("iat missing", tokenWith("-iat"));
        one("exp string", tokenWith("exp=\"mañana\""));
        one("exp numeric string", tokenWith("exp=\"" + (NOW_S + 600) + "\""));
        one("exp null", tokenWith("exp=null"));
        one("exp bool", tokenWith("exp=true"));
        one("exp array", tokenWith("exp=[1]"));
        one("exp negative", tokenWith("exp=-5"));
        one("exp negative zero", tokenWith("exp=-0"));
        one("exp negative zero float", tokenWith("exp=-0.0"));
        one("exp zero", tokenWith("exp=0", "iat=0"));
        one("exp float", tokenWith("exp=" + (NOW_S + 600) + ".9"));
        one("exp float exponent", tokenWith("exp=1.7e9", "iat=1.7e9".replace("1.7e9", Long.toString(NOW_S))));
        one("exp exponent big (year 9999 max)", tokenWith("exp=253402300799", "iat=253402300000"));
        one("exp just over max", tokenWith("exp=253402300800", "iat=253402300000"));
        one("exp 1e400", tokenWith("exp=1e400"));
        one("exp -1e400", tokenWith("exp=-1e400"));
        one("exp huge integer", tokenWith("exp=" + "9".repeat(30)));
        one("exp huge integer 400 digits", tokenWith("exp=" + "9".repeat(400)));
        one("exp 2^63", tokenWith("exp=9223372036854775808"));
        one("exp 2^53+1 precision", tokenWith("exp=9007199254740993"));
        one("iat float", tokenWith("iat=" + NOW_S + ".5"));
        one("iat string", tokenWith("iat=\"x\""));
        one("iat future beyond skew", tokenWith("iat=" + (NOW_S + 31), "exp=" + (NOW_S + 600)));
        one("iat future at skew", tokenWith("iat=" + (NOW_S + 30), "exp=" + (NOW_S + 600)));
        one("exp at now minus skew", tokenWith("exp=" + (NOW_S - 30), "iat=" + (NOW_S - 600)));
        one("exp at now minus skew plus 1", tokenWith("exp=" + (NOW_S - 29), "iat=" + (NOW_S - 600)));
        one("exp expired long ago", tokenWith("exp=" + (NOW_S - 4000), "iat=" + (NOW_S - 4500)));
        one("exp float just under skew", tokenWith("exp=" + (NOW_S - 29) + ".9", "iat=" + (NOW_S - 600)));
        one("nbf future beyond skew", tokenWith("nbf=" + (NOW_S + 31)));
        one("nbf future at skew", tokenWith("nbf=" + (NOW_S + 30)));
        one("nbf past", tokenWith("nbf=" + (NOW_S - 100)));
        one("nbf null", tokenWith("nbf=null"));
        one("nbf string", tokenWith("nbf=\"x\""));
        one("nbf negative", tokenWith("nbf=-1"));
        one("nbf huge", tokenWith("nbf=253402300800"));
        one("nbf float future", tokenWith("nbf=" + (NOW_S + 30) + ".9"));
        one("lifetime exactly 900", tokenWith("iat=" + NOW_S, "exp=" + (NOW_S + 900)));
        one("lifetime 901", tokenWith("iat=" + NOW_S, "exp=" + (NOW_S + 901)));
        one("lifetime float 900.9", tokenWith("iat=" + NOW_S, "exp=" + (NOW_S + 900) + ".9"));
        one("exp equal to iat", tokenWith("iat=" + NOW_S, "exp=" + NOW_S));
        one("exp before iat", tokenWith("iat=" + NOW_S, "exp=" + (NOW_S - 1)));
        one("exp 1 after iat", tokenWith("iat=" + NOW_S, "exp=" + (NOW_S + 1)));
        one("iat float exp same second", tokenWith("iat=" + NOW_S + ".1", "exp=" + NOW_S + ".9"));
        // orden de las comprobaciones de claims
        one("order: exp missing before audience", tokenWith("-exp", "aud=\"x\""));
        one("order: iat missing before exp wrong type", tokenWith("-iat", "exp=\"x\""));
        one("order: exp wrong type before iat missing", tokenWith("exp=\"x\"", "-iat"));
        one("order: nbf wrong type before expired", tokenWith("nbf=\"x\"", "exp=" + (NOW_S - 4000), "iat=" + (NOW_S - 4500)));
        one("order: expired before issuer", tokenWith("exp=" + (NOW_S - 4000), "iat=" + (NOW_S - 4500), "iss=\"otra\""));
        one("order: not yet valid before lifetime", tokenWith("iat=" + (NOW_S + 100), "exp=" + (NOW_S + 5000)));
        one("order: lifetime before issuer", tokenWith("exp=" + (NOW_S + 5000), "iss=\"otra\""));
        one("order: issuer before audience", tokenWith("iss=\"otra\"", "aud=\"x\""));
        one("order: audience before sub", tokenWith("aud=\"x\"", "-sub"));
        one("order: sub before courses", tokenWith("-sub", "-courses"));
        one("order: nbf future before iat future wins both", tokenWith("nbf=" + (NOW_S + 100), "iat=" + (NOW_S + 100), "exp=" + (NOW_S + 400)));
        // duplicados en el payload
        one("payload duplicate sub", signed(H, "{\"iss\":\"" + ISS + "\",\"aud\":\"" + AUD + "\",\"sub\":\"a\",\"sub\":\"b\",\"courses\":[],\"iat\":" + NOW_S + ",\"exp\":" + (NOW_S + 600) + "}"));
        one("payload duplicate unrelated", signed(H, claims().substring(0, claims().length() - 1) + ",\"x\":1,\"x\":2}"));
        one("payload duplicate nested", signed(H, claims().substring(0, claims().length() - 1) + ",\"x\":{\"a\":1,\"a\":2}}"));
        one("payload not an object", signed(H, "[]"));
        one("payload string", signed(H, "\"x\""));
        one("payload null", signed(H, "null"));
        one("payload empty", signed(H, ""));
        one("payload trailing garbage", signed(H, claims() + "xx"));
        one("payload trailing object", signed(H, claims() + "{}"));
        one("payload truncated", signed(H, claims().substring(0, claims().length() - 1)));
        one("payload with BOM", signedBytes(utf8(H), concat(new byte[] {(byte) 0xef, (byte) 0xbb, (byte) 0xbf}, utf8(claims()))));
        one("payload invalid utf-8", signedBytes(utf8(H), concat(utf8(claims().substring(0, claims().length() - 1) + ",\"x\":\""), new byte[] {(byte) 0xc3}, utf8("\"}"))));
        one("payload utf-16le", signedBytes(utf8(H), claims().getBytes(StandardCharsets.UTF_16LE)));
        one("payload with whitespace", signed(H, " \n\t" + claims().replace(",", " , ") + "\r\n"));
        // firma
        String base = tokenWith();
        byte[] sig = Base64.getUrlDecoder().decode(base.substring(base.lastIndexOf('.') + 1));
        String signingInput = base.substring(0, base.lastIndexOf('.'));
        one("signature valid again", base);
        one("signature tampered last byte", withSig(base, flip(sig, 63, 0x01)));
        one("signature tampered first byte", withSig(base, flip(sig, 0, 0x80)));
        one("signature all zeros", withSig(base, new byte[64]));
        one("signature 63 bytes", withSig(base, Arrays.copyOf(sig, 63)));
        one("signature 65 bytes", withSig(base, Arrays.copyOf(sig, 65)));
        one("signature empty is malformed", base.substring(0, base.lastIndexOf('.')) + ".");
        one("signature other key", signedWith(SEED_OTHER, H, claims()));
        one("signature k2 claiming k1", signedWith(SEED2, H, claims()));
        add("signature k1 claiming k2 (two keys)", "two", signedWith(SEED1, "{\"alg\":\"EdDSA\",\"kid\":\"k2\"}", claims()));
        one("payload altered after signing", parts(base)[0] + "." + b64(utf8(claims("sub=\"otro\""))) + "." + parts(base)[2]);
        one("header altered after signing", b64(utf8("{\"alg\":\"EdDSA\",\"typ\":\"JWT\",\"kid\":\"k1\",\"x\":1}")) + "." + parts(base)[1] + "." + parts(base)[2]);
        // S + L (no canónica) y S = L, S >= L
        byte[] s = Arrays.copyOfRange(sig, 32, 64);
        BigInteger sInt = new BigInteger(1, reverse(s));
        one("S plus L (malleable)", withSig(base, withS(sig, sInt.add(L))));
        one("S equals L", withSig(base, withS(sig, L)));
        one("S minus L plus L again", withSig(base, withS(sig, sInt.add(L).add(L))));
        one("S = 2^256-1", withSig(base, withS(sig, BigInteger.ONE.shiftLeft(256).subtract(BigInteger.ONE))));
        one("S with top bits set", withSig(base, withSTop(sig)));
        // R: punto de orden pequeño y no canónico
        byte[] identityR = new byte[32];
        identityR[0] = 1;
        one("R identity, S zero", withSig(base, concat(identityR, new byte[32])));
        one("R identity, S original", withSig(base, concat(identityR, s)));
        byte[] order2 = new byte[32];
        Arrays.fill(order2, (byte) 0xff);
        order2[0] = (byte) 0xec;
        order2[31] = 0x7f; // y = -1 (p - 1): punto de orden 2
        one("R order 2, S zero", withSig(base, concat(order2, new byte[32])));
        byte[] nonCanonicalIdentity = new byte[32];
        Arrays.fill(nonCanonicalIdentity, (byte) 0xff);
        nonCanonicalIdentity[0] = (byte) 0xee;
        nonCanonicalIdentity[31] = 0x7f; // y = p + 1 (no canónico) = identidad
        one("R non-canonical identity, S zero", withSig(base, concat(nonCanonicalIdentity, new byte[32])));
        byte[] garbageR = new byte[32];
        Arrays.fill(garbageR, (byte) 0xff);
        one("R not a point", withSig(base, concat(garbageR, s)));
        byte[] flippedR = Arrays.copyOf(sig, 64);
        flippedR[31] ^= (byte) 0x80; // signo de x
        one("R with flipped sign bit", withSig(base, flippedR));
        // bits sueltos
        for (int bit : new int[] {0, 1, 7, 8, 100, 200, 255, 256, 300, 400, 511}) {
            one("signature bit flip " + bit, withSig(base, flip(sig, bit / 8, 1 << (bit % 8))));
        }
        // la firma es lo último
        one("bad signature and expired: expired wins", withSig(tokenWith("exp=" + (NOW_S - 4000), "iat=" + (NOW_S - 4500)), new byte[64]));
        one("bad signature and wrong audience: audience wins", withSig(tokenWith("aud=\"x\""), new byte[64]));
        one("bad signature length and malformed payload: signature wins", signedBytes(utf8(H), utf8("[")).substring(0, signedBytes(utf8(H), utf8("[")).lastIndexOf('.') + 1) + b64(new byte[10]));
        one("bad signature base64 and malformed payload", b64(utf8(H)) + "." + b64(utf8("[")) + ".!!");
        one("malformed payload and good signature", signed(H, "["));
        one("signature over a different input than sent", b64(utf8(H)) + "." + b64(utf8(claims())) + "." + b64(sign(SEED1, signingInput + "x")));
        one("signature checked over the exact encoded segments (padded header)", paddedHeaderToken());
        one("sub with spaces and bad signature (claims first)", withSig(tokenWith("sub=\"  \""), new byte[64]));
        // unicode/escapes de nombres
        one("claim name with escape", signed(H, "{\"i\\u0073s\":\"" + ISS + "\",\"aud\":\"" + AUD + "\",\"sub\":\"est-1\",\"courses\":[\"MAT\"],\"iat\":" + NOW_S + ",\"exp\":" + (NOW_S + 600) + "}"));
        one("claim name case differs", signed(H, "{\"ISS\":\"" + ISS + "\",\"aud\":\"" + AUD + "\",\"sub\":\"est-1\",\"courses\":[\"MAT\"],\"iat\":" + NOW_S + ",\"exp\":" + (NOW_S + 600) + "}"));
        one("courses with escaped chars", tokenWith("courses=[\"M\\u0041T\",\"F\\/S\"]"));
        one("courses with a quote", tokenWith("courses=[\"M\\\"T\"]"));
        one("sub with newline", tokenWith("sub=\"a\\nb\""));
        // dos llaves: kid vs sin kid
        add("two keys: valid k1", "two", valid);
        add("two keys: kid unknown", "two", signed("{\"alg\":\"EdDSA\",\"kid\":\"k3\"}", claims()));
        add("two keys: kid null", "two", signed("{\"alg\":\"EdDSA\",\"kid\":null}", claims()));
    }

    /**
     * Java conserva un sustituto suelto ({@code \\ud800}) de un escape JSON; un {@code String} de Rust no
     * puede contenerlo y lo reemplaza por U+FFFD. El fixture ya trae el reemplazo para poder compararse.
     */
    private static String noLoneSurrogates(String text) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isHighSurrogate(c) && i + 1 < text.length() && Character.isLowSurrogate(text.charAt(i + 1))) {
                out.append(c).append(text.charAt(++i));
            } else if (Character.isSurrogate(c)) {
                out.append('\uFFFD');
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }

    private static String paddedHeaderToken() {
        String h = b64(utf8(H));
        int pads = (4 - h.length() % 4) % 4;
        String paddedHeader = h + "=".repeat(pads);
        String payload = b64(utf8(claims()));
        String input = paddedHeader + "." + payload;
        return input + "." + b64(sign(SEED1, input));
    }

    private static String lastCharVariant(String[] parts) {
        String header = parts[0];
        // Cambia el último carácter por otro que solo difiere en los bits de relleno.
        String alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_";
        int index = alphabet.indexOf(header.charAt(header.length() - 1));
        int pads = (4 - header.length() % 4) % 4;
        int mask = pads == 2 ? 0x0f : pads == 1 ? 0x03 : 0;
        char changed = alphabet.charAt(index ^ (mask == 0 ? 0 : 1));
        return header.substring(0, header.length() - 1) + changed + "." + parts[1] + "." + parts[2];
    }

    /** Token válido de exactamente {@code length} caracteres (relleno en el payload y, si hace falta, en la cabecera). */
    private static String exactLength(int length) {
        for (int headerPad = 0; headerPad < 4; headerPad++) {
            String header = "{\"alg\":\"EdDSA\",\"typ\":\"JWT\",\"kid\":\"k1\",\"h\":\"" + "h".repeat(headerPad) + "\"}";
            for (int pad = 0; pad < 9000; pad++) {
                String t = signed(header, claims("pad=\"" + "p".repeat(pad) + "\""));
                if (t.length() == length) {
                    return t;
                }
                if (t.length() > length) {
                    break;
                }
            }
        }
        throw new IllegalStateException("no se logró un token de " + length + " caracteres");
    }

    private static String[] parts(String token) {
        return token.split("\\.");
    }

    private static String withSig(String token, byte[] signature) {
        return token.substring(0, token.lastIndexOf('.') + 1) + b64(signature);
    }

    private static byte[] flip(byte[] bytes, int index, int mask) {
        byte[] out = bytes.clone();
        out[index] ^= (byte) mask;
        return out;
    }

    private static byte[] reverse(byte[] bytes) {
        byte[] out = new byte[bytes.length];
        for (int i = 0; i < bytes.length; i++) {
            out[i] = bytes[bytes.length - 1 - i];
        }
        return out;
    }

    private static byte[] withS(byte[] sig, BigInteger s) {
        byte[] out = sig.clone();
        byte[] big = s.toByteArray();
        byte[] le = new byte[32];
        for (int i = 0; i < 32; i++) {
            int src = big.length - 1 - i;
            le[i] = src >= 0 ? big[src] : 0;
        }
        System.arraycopy(le, 0, out, 32, 32);
        return out;
    }

    private static byte[] withSTop(byte[] sig) {
        byte[] out = sig.clone();
        out[63] |= (byte) 0xe0;
        return out;
    }

    private static byte[] concat(byte[]... arrays) {
        int total = 0;
        for (byte[] a : arrays) {
            total += a.length;
        }
        byte[] out = new byte[total];
        int offset = 0;
        for (byte[] a : arrays) {
            System.arraycopy(a, 0, out, offset, a.length);
            offset += a.length;
        }
        return out;
    }
}
