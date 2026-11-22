package sched.server;

import io.grpc.InsecureServerCredentials;
import io.grpc.ServerCredentials;
import io.grpc.TlsServerCredentials;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.cert.CertificateFactory;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Credenciales del gRPC de ingesta. Con {@code SCHED_GRPC_TLS=required} (por defecto) exige TLS
 * con autenticación mutua: el servidor presenta su certificado y solo acepta clientes cuyo
 * certificado esté firmado por la CA de la institución ({@code ClientAuth.REQUIRE}).
 */
final class GrpcCredentials {

    private static final Logger log = LoggerFactory.getLogger(GrpcCredentials.class);

    private GrpcCredentials() {
    }

    /**
     * @param mode {@code required} o {@code disabled}
     * @param certChain PEM con el certificado del servidor (y su cadena)
     * @param privateKey PEM (PKCS#8) con la llave privada del servidor
     * @param clientCa PEM con la CA que firma los certificados de cliente
     * @throws IllegalStateException con un mensaje claro si falta algún archivo en modo {@code required}
     */
    static ServerCredentials server(String mode, String certChain, String privateKey, String clientCa) {
        return switch (mode) {
            case "disabled" -> {
                log.warn("SCHED_GRPC_TLS=disabled: la ingesta gRPC escucha SIN TLS ni autenticación de la "
                        + "institución. Úselo solo en desarrollo");
                yield InsecureServerCredentials.create();
            }
            case "required" -> mutualTls(certChain, privateKey, clientCa);
            default -> throw new IllegalArgumentException(
                    "SCHED_GRPC_TLS inválido: '" + mode + "' (use required o disabled)");
        };
    }

    private static ServerCredentials mutualTls(String certChain, String privateKey, String clientCa) {
        File cert = readable("SCHED_GRPC_TLS_CERT", certChain);
        File key = readable("SCHED_GRPC_TLS_KEY", privateKey);
        File ca = readable("SCHED_GRPC_TLS_CLIENT_CA", clientCa);
        // gRPC lee los archivos recién al abrir el puerto; se comprueban aquí para fallar al arrancar.
        checkCertificates("SCHED_GRPC_TLS_CERT", cert);
        checkCertificates("SCHED_GRPC_TLS_CLIENT_CA", ca);
        checkPrivateKey("SCHED_GRPC_TLS_KEY", key);
        try {
            ServerCredentials credentials = TlsServerCredentials.newBuilder()
                    .keyManager(cert, key)
                    .trustManager(ca)
                    .clientAuth(TlsServerCredentials.ClientAuth.REQUIRE)
                    .build();
            log.info("ingesta gRPC con TLS y autenticación mutua (certificado de cliente obligatorio)");
            return credentials;
        } catch (IOException | RuntimeException e) {
            throw new IllegalStateException("No se pudo cargar el certificado, la llave o la CA del gRPC de "
                    + "ingesta (¿PEM válidos? la llave debe ser PKCS#8): " + e.getMessage(), e);
        }
    }

    private static void checkCertificates(String variable, File file) {
        try (InputStream in = Files.newInputStream(file.toPath())) {
            if (CertificateFactory.getInstance("X.509").generateCertificates(in).isEmpty()) {
                throw new IllegalStateException(variable + ": " + file + " no contiene ningún certificado PEM");
            }
        } catch (IOException | GeneralSecurityException e) {
            throw new IllegalStateException(variable + ": " + file + " no es un certificado PEM válido", e);
        }
    }

    private static void checkPrivateKey(String variable, File file) {
        try {
            String pem = Files.readString(file.toPath(), StandardCharsets.US_ASCII);
            String begin = "-----BEGIN PRIVATE KEY-----";
            int start = pem.indexOf(begin);
            int end = pem.indexOf("-----END PRIVATE KEY-----");
            if (start < 0 || end < start) {
                throw new IllegalStateException(variable + ": " + file
                        + " debe ser una llave privada PEM en formato PKCS#8 (BEGIN PRIVATE KEY)");
            }
            var spec = new PKCS8EncodedKeySpec(
                    Base64.getMimeDecoder().decode(pem.substring(start + begin.length(), end).strip()));
            for (String algorithm : new String[] {"EC", "RSA", "Ed25519"}) {
                try {
                    KeyFactory.getInstance(algorithm).generatePrivate(spec);
                    return;
                } catch (InvalidKeySpecException _) {
                    // se prueba con el siguiente algoritmo
                }
            }
            throw new IllegalStateException(variable + ": " + file + " no es una llave privada PKCS#8 válida");
        } catch (IOException | GeneralSecurityException | IllegalArgumentException e) {
            throw new IllegalStateException(variable + ": " + file + " no es una llave privada PEM válida", e);
        }
    }

    private static File readable(String variable, String path) {
        if (path == null || path.isBlank()) {
            throw new IllegalStateException(variable + " no está configurado. La ingesta gRPC exige TLS con "
                    + "autenticación mutua: configure SCHED_GRPC_TLS_CERT, SCHED_GRPC_TLS_KEY y "
                    + "SCHED_GRPC_TLS_CLIENT_CA (ver deploy/certs/generate.sh), o SCHED_GRPC_TLS=disabled solo "
                    + "para desarrollo");
        }
        File file = new File(path);
        if (!file.isFile() || !file.canRead()) {
            throw new IllegalStateException(variable + ": no se puede leer el archivo " + path);
        }
        return file;
    }
}
