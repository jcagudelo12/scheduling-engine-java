package sched.simulation;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.json.JsonMapper;

/**
 * Herramientas de desarrollo para el token firmado (ADR 0004): generan un par de llaves Ed25519
 * y firman tokens de prueba. Con el JDK y Jackson, sin librerías JWT. Lo que hace la institución
 * en producción es lo mismo, con su propia llave privada.
 *
 * <p>No son para producción: el token se firma con una llave que está en un archivo local.
 */
final class DevAuth {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private DevAuth() {
    }

    /**
     * Escribe {@code <kid>.key} (privada, PKCS#8) y {@code <kid>.pem} (pública, X.509) en
     * {@code dir}. El motor carga la pública por su nombre: {@code SCHED_JWT_PUBLIC_KEYS=<dir>}.
     */
    static void keygen(Path dir, String kid) throws IOException, GeneralSecurityException {
        if (!kid.matches("[A-Za-z0-9._-]{1,64}")) {
            throw new IllegalArgumentException("kid inválido: use letras, dígitos, '.', '_' y '-' (hasta 64)");
        }
        Files.createDirectories(dir);
        Path priv = dir.resolve(kid + ".key");
        Path pub = dir.resolve(kid + ".pem");
        if (Files.exists(priv) || Files.exists(pub)) {
            throw new IllegalArgumentException("ya existe " + priv + " o " + pub + ": no se sobrescribe");
        }
        KeyPair pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        Files.writeString(priv, pem("PRIVATE KEY", pair.getPrivate().getEncoded()));
        try {
            Files.setPosixFilePermissions(priv, PosixFilePermissions.fromString("rw-------"));
        } catch (UnsupportedOperationException _) {
            // sistema de archivos sin permisos POSIX (Windows)
        }
        Files.writeString(pub, pem("PUBLIC KEY", pair.getPublic().getEncoded()));
    }

    /** Un JWS compacto EdDSA con {@code sub}, {@code courses}, {@code iss}, {@code aud}, {@code iat} y {@code exp}. */
    static String token(Path privateKeyPem, String kid, String student, List<String> courses,
            String issuer, String audience, long ttlSeconds, Instant now) throws IOException, GeneralSecurityException {
        PrivateKey key = readPrivateKey(privateKeyPem);
        Map<String, Object> header = new LinkedHashMap<>();
        header.put("alg", "EdDSA");
        header.put("typ", "JWT");
        header.put("kid", kid);
        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put("iss", issuer);
        claims.put("aud", audience);
        claims.put("sub", student);
        claims.put("courses", courses);
        claims.put("iat", now.getEpochSecond());
        claims.put("exp", now.plusSeconds(ttlSeconds).getEpochSecond());

        String input = b64(JSON.writeValueAsBytes(header)) + "." + b64(JSON.writeValueAsBytes(claims));
        Signature signer = Signature.getInstance("Ed25519");
        signer.initSign(key);
        signer.update(input.getBytes(StandardCharsets.US_ASCII));
        return input + "." + b64(signer.sign());
    }

    private static PrivateKey readPrivateKey(Path file) throws IOException, GeneralSecurityException {
        String pem = Files.readString(file, StandardCharsets.US_ASCII);
        String begin = "-----BEGIN PRIVATE KEY-----";
        int start = pem.indexOf(begin);
        int end = pem.indexOf("-----END PRIVATE KEY-----");
        if (start < 0 || end < start) {
            throw new IllegalArgumentException(file + " no es una llave privada PEM PKCS#8 (BEGIN PRIVATE KEY)");
        }
        byte[] der = Base64.getMimeDecoder().decode(pem.substring(start + begin.length(), end).strip());
        return KeyFactory.getInstance("Ed25519").generatePrivate(new PKCS8EncodedKeySpec(der));
    }

    private static String b64(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String pem(String label, byte[] der) {
        return "-----BEGIN " + label + "-----\n"
                + Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII)).encodeToString(der)
                + "\n-----END " + label + "-----\n";
    }
}
