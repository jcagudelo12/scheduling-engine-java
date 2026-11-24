package sched.adapters.http.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PublicKeysTest {

    @TempDir
    Path dir;

    private Path write(String name, String content) throws Exception {
        Path file = dir.resolve(name);
        Files.writeString(file, content);
        return file;
    }

    @Test
    void loadsExplicitKidFilesAndDirectories() throws Exception {
        KeyPair a = TestTokens.newKeyPair();
        KeyPair b = TestTokens.newKeyPair();
        Path fa = write("a.pem", TestTokens.pem(a.getPublic()));
        Path sub = Files.createDirectory(dir.resolve("llaves"));
        Files.writeString(sub.resolve("dir-1.pem"), TestTokens.pem(b.getPublic()));
        Files.writeString(sub.resolve("dir-1.key"), "no es una .pem, se ignora");

        assertEquals(Set.of("explicito"), PublicKeys.load("explicito=" + fa).keySet());
        assertEquals(Set.of("a"), PublicKeys.load(fa.toString()).keySet());
        assertEquals(Set.of("dir-1"), PublicKeys.load(sub.toString()).keySet());
        assertEquals(Set.of("explicito", "dir-1"), PublicKeys.load("explicito=" + fa + ", " + sub).keySet());
        assertEquals(b.getPublic(), PublicKeys.load(sub.toString()).get("dir-1"));
    }

    @Test
    void failsClearlyWhenThereAreNoKeys() throws Exception {
        assertTrue(assertThrows(IllegalArgumentException.class, () -> PublicKeys.load("")).getMessage()
                .contains("SCHED_JWT_PUBLIC_KEYS"));
        assertThrows(IllegalArgumentException.class, () -> PublicKeys.load(null));
        Path empty = Files.createDirectory(dir.resolve("vacio"));
        assertThrows(IllegalArgumentException.class, () -> PublicKeys.load(empty.toString()));
        assertThrows(IllegalArgumentException.class, () -> PublicKeys.load("k=" + dir.resolve("no-existe.pem")));
    }

    @Test
    void rejectsPrivateKeysGarbageAndOtherAlgorithms() throws Exception {
        Path priv = write("p.pem", "-----BEGIN PRIVATE KEY-----\nAAAA\n-----END PRIVATE KEY-----\n");
        Path junk = write("j.pem", "-----BEGIN PUBLIC KEY-----\nAAAA\n-----END PUBLIC KEY-----\n");
        var rsa = java.security.KeyPairGenerator.getInstance("RSA");
        rsa.initialize(2048);
        Path rsaPem = write("r.pem", TestTokens.pem(rsa.generateKeyPair().getPublic()));

        for (Path bad : new Path[] {priv, junk, rsaPem}) {
            assertThrows(IllegalArgumentException.class, () -> PublicKeys.load("k=" + bad));
        }
    }

    @Test
    void rejectsDuplicateAndInvalidKids() throws Exception {
        Path f = write("a.pem", TestTokens.pem(TestTokens.newKeyPair().getPublic()));

        assertThrows(IllegalArgumentException.class, () -> PublicKeys.load("k=" + f + ",k=" + f));
        assertThrows(IllegalArgumentException.class, () -> PublicKeys.load("mal kid=" + f));
    }
}
