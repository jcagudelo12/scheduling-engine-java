package sched.adapters.nats;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.nats.client.Options;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class NatsConnectorTest {

    @TempDir
    Path dir;

    @Test
    void withoutCredentialsAnOpenServerIsAllowed() {
        Options options = NatsConnector.options("nats://localhost:4222", "", "", "", "");

        assertEquals(1, options.getServers().size());
        assertTrue(options.getServers().iterator().next().toString().startsWith("nats://"));
    }

    @Test
    void userAndTokenAreMutuallyExclusive() {
        assertThrows(IllegalArgumentException.class,
                () -> NatsConnector.options("nats://h:4222", "motor", "clave", "tok", ""));
    }

    @Test
    void passwordAndUserGoTogetherAndErrorsDoNotLeakSecrets() {
        var noPassword = assertThrows(IllegalArgumentException.class,
                () -> NatsConnector.options("nats://h:4222", "motor", "", "", ""));
        var noUser = assertThrows(IllegalArgumentException.class,
                () -> NatsConnector.options("nats://h:4222", "", "S3CRETO", "", ""));

        assertFalse(noUser.getMessage().contains("S3CRETO"));
        assertTrue(noPassword.getMessage().contains("SCHED_NATS_PASSWORD"));
    }

    @Test
    void aCustomCaTurnsTheConnectionIntoTls() throws Exception {
        Path missing = dir.resolve("no-existe.pem");
        assertThrows(IllegalArgumentException.class,
                () -> NatsConnector.options("nats://h:4222", "u", "p", "", missing.toString()));

        Path notACert = Files.writeString(dir.resolve("x.pem"), "no es un certificado");
        assertThrows(IllegalArgumentException.class,
                () -> NatsConnector.options("nats://h:4222", "u", "p", "", notACert.toString()));
    }
}
