package sched.adapters.nats;

import io.nats.client.Connection;
import io.nats.client.Nats;
import io.nats.client.Options;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Conexión del motor a NATS con credenciales y TLS opcional. Admite usuario y contraseña, o un
 * token (no ambos). Con una CA propia ({@code SCHED_NATS_TLS_CA}) la conexión va cifrada y solo
 * confía en esa CA. Nunca registra ni incluye en un mensaje de error las credenciales.
 */
public final class NatsConnector {

    private static final Logger log = LoggerFactory.getLogger(NatsConnector.class);

    private NatsConnector() {
    }

    /**
     * @param user usuario (vacío o {@code null} si no se usa)
     * @param password contraseña del usuario
     * @param token token de autenticación (alternativa a usuario y contraseña)
     * @param tlsCa ruta de un PEM con la CA que firmó el certificado de NATS; vacío = sin TLS propio
     */
    public static Connection connect(String url, String user, String password, String token, String tlsCa)
            throws IOException, InterruptedException {
        Options options = options(url, user, password, token, tlsCa);
        log.info("conectando a NATS {} (autenticación: {}, TLS: {})", url, authKind(user, token),
                isBlank(tlsCa) ? "no" : "sí, CA propia");
        return Nats.connect(options);
    }

    /** Construye las opciones de conexión; separado de {@link #connect} para probarlo sin servidor. */
    static Options options(String url, String user, String password, String token, String tlsCa) {
        boolean hasUser = !isBlank(user);
        boolean hasToken = !isBlank(token);
        if (hasUser && hasToken) {
            throw new IllegalArgumentException(
                    "SCHED_NATS_TOKEN y SCHED_NATS_USER son excluyentes: configure solo uno");
        }
        if (hasUser && isBlank(password)) {
            throw new IllegalArgumentException("SCHED_NATS_USER requiere SCHED_NATS_PASSWORD");
        }
        if (!hasUser && !isBlank(password)) {
            throw new IllegalArgumentException("SCHED_NATS_PASSWORD requiere SCHED_NATS_USER");
        }
        boolean tls = !isBlank(tlsCa);
        // Con CA propia, la conexión debe ir cifrada: nats:// pasa a tls://.
        String serverUrl = tls && url.startsWith("nats://") ? "tls://" + url.substring("nats://".length()) : url;
        Options.Builder builder = new Options.Builder().server(serverUrl);
        if (hasUser) {
            builder.userInfo(user, password);
        } else if (hasToken) {
            builder.token(token.toCharArray());
        }
        if (tls) {
            builder.sslContext(sslContext(Path.of(tlsCa)));
        }
        return builder.build();
    }

    /** Un {@code SSLContext} que confía solo en las CA del PEM indicado. */
    static SSLContext sslContext(Path caFile) {
        try (InputStream in = Files.newInputStream(caFile)) {
            KeyStore trust = KeyStore.getInstance(KeyStore.getDefaultType());
            trust.load(null, null);
            int i = 0;
            for (Certificate cert : CertificateFactory.getInstance("X.509").generateCertificates(in)) {
                trust.setCertificateEntry("ca-" + i++, cert);
            }
            if (i == 0) {
                throw new IllegalArgumentException("SCHED_NATS_TLS_CA no contiene ningún certificado: " + caFile);
            }
            TrustManagerFactory factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            factory.init(trust);
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(null, factory.getTrustManagers(), null);
            return context;
        } catch (IOException | GeneralSecurityException e) {
            throw new IllegalArgumentException(
                    "No se pudo cargar la CA de NATS (SCHED_NATS_TLS_CA=" + caFile + "): " + e.getMessage(), e);
        }
    }

    private static String authKind(String user, String token) {
        return !isBlank(user) ? "usuario" : !isBlank(token) ? "token" : "ninguna";
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
