package sched.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class AuthModeTest {

    @Test
    void onlyJwtAndNoneAreValid() {
        assertEquals(AuthMode.JWT, AuthMode.parse("jwt"));
        assertEquals(AuthMode.NONE, AuthMode.parse("none"));
        // Un error de escritura no debe dejar la API abierta sin avisar: no arranca.
        for (String bad : new String[] {"", "JWT", "None", "off", "false", "jwtt"}) {
            assertThrows(IllegalArgumentException.class, () -> AuthMode.parse(bad), bad);
        }
    }
}
