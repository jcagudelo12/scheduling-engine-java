package sched.adapters.http.auth;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Carga las llaves públicas Ed25519 con que la institución firma los tokens (ADR 0004).
 *
 * <p>Formato de {@code SCHED_JWT_PUBLIC_KEYS}: lista separada por comas. Cada elemento es
 * {@code kid=ruta} (un archivo PEM), una ruta a un archivo PEM (el {@code kid} es el nombre
 * sin extensión) o una ruta a un directorio (cada {@code *.pem} es una llave y su {@code kid} es
 * el nombre sin extensión). Varias llaves permiten rotar sin cortes: se publica la nueva, la
 * institución empieza a firmar con ella y después se retira la anterior.
 *
 * <p>El PEM es {@code -----BEGIN PUBLIC KEY-----} (X.509 SubjectPublicKeyInfo), el que produce
 * {@code openssl pkey -pubout}. Una llave privada se rechaza.
 */
public final class PublicKeys {

    /** Forma permitida de un {@code kid}: se registra en los logs y se compara con la cabecera. */
    public static final Pattern KID = Pattern.compile("[A-Za-z0-9._-]{1,64}");

    private static final String BEGIN = "-----BEGIN PUBLIC KEY-----";
    private static final String END = "-----END PUBLIC KEY-----";

    private PublicKeys() {
    }

    /** @throws IllegalArgumentException con un mensaje claro si falta algo o una llave no es válida */
    public static Map<String, PublicKey> load(String spec) {
        if (spec == null || spec.isBlank()) {
            throw new IllegalArgumentException(
                    "SCHED_JWT_PUBLIC_KEYS no está configurado: indique las llaves públicas Ed25519 con que la "
                            + "institución firma los tokens (kid=ruta.pem, un archivo o un directorio de *.pem), "
                            + "o use SCHED_AUTH=none solo para desarrollo");
        }
        Map<String, PublicKey> keys = new LinkedHashMap<>();
        for (String entry : spec.split(",")) {
            entry = entry.trim();
            if (entry.isEmpty()) {
                continue;
            }
            int eq = entry.indexOf('=');
            if (eq > 0) {
                add(keys, entry.substring(0, eq).trim(), Path.of(entry.substring(eq + 1).trim()));
            } else if (Files.isDirectory(Path.of(entry))) {
                try (Stream<Path> files = Files.list(Path.of(entry))) {
                    files.filter(p -> p.getFileName().toString().endsWith(".pem")).sorted()
                            .forEach(p -> add(keys, stripExtension(p), p));
                } catch (IOException e) {
                    throw new IllegalArgumentException("No se pudo leer el directorio de llaves " + entry
                            + ": " + e.getMessage(), e);
                }
            } else {
                Path file = Path.of(entry);
                add(keys, stripExtension(file), file);
            }
        }
        if (keys.isEmpty()) {
            throw new IllegalArgumentException(
                    "SCHED_JWT_PUBLIC_KEYS no contiene ninguna llave (¿directorio sin archivos *.pem?)");
        }
        return Map.copyOf(keys);
    }

    private static void add(Map<String, PublicKey> keys, String kid, Path file) {
        if (!KID.matcher(kid).matches()) {
            throw new IllegalArgumentException("kid inválido '" + kid + "' para " + file
                    + " (letras, dígitos, '.', '_' y '-', hasta 64 caracteres)");
        }
        PublicKey key;
        try {
            key = parsePem(Files.readString(file, StandardCharsets.US_ASCII));
        } catch (IOException e) {
            throw new IllegalArgumentException("No se pudo leer la llave '" + kid + "' en " + file
                    + ": " + e.getMessage(), e);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("La llave '" + kid + "' en " + file + " no es válida: "
                    + e.getMessage(), e);
        }
        if (keys.put(kid, key) != null) {
            throw new IllegalArgumentException("kid repetido en SCHED_JWT_PUBLIC_KEYS: " + kid);
        }
    }

    private static String stripExtension(Path file) {
        String name = file.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    /** Interpreta un PEM {@code PUBLIC KEY} que contiene una llave Ed25519. */
    public static PublicKey parsePem(String pem) {
        if (pem.contains("PRIVATE KEY")) {
            throw new IllegalArgumentException("es una llave privada; se espera la pública (BEGIN PUBLIC KEY)");
        }
        int start = pem.indexOf(BEGIN);
        int end = pem.indexOf(END);
        if (start < 0 || end < start) {
            throw new IllegalArgumentException("no es un PEM 'BEGIN PUBLIC KEY'");
        }
        byte[] der;
        try {
            der = Base64.getMimeDecoder().decode(pem.substring(start + BEGIN.length(), end).strip());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("el contenido PEM no es base64 válido");
        }
        try {
            return KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(der));
        } catch (GeneralSecurityException e) {
            throw new IllegalArgumentException("no es una llave pública Ed25519");
        }
    }
}
