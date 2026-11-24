package sched.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import org.junit.jupiter.api.Test;

/** {@code SCHED_WARMUP*}: valores inválidos impiden arrancar con un mensaje claro; en {@code ingest} no hay calentamiento. */
class WarmupSettingsTest {

    @Test
    void parsesOnAndOff() {
        WarmupSettings on = WarmupSettings.parse("on", "on", 10, 60, 0);
        assertTrue(on.enabled());
        assertEquals(Duration.ofSeconds(10), on.min());
        assertEquals(Duration.ofSeconds(60), on.max());
        assertTrue(on.effectiveThreads() >= 1);
        assertFalse(WarmupSettings.parse("off", "on", 10, 60, 0).enabled());
        assertEquals(3, WarmupSettings.parse("on", "off", 0, 1, 3).effectiveThreads());
    }

    @Test
    void invalidValuesFailWithAClearMessage() {
        assertTrue(assertThrows(IllegalArgumentException.class, () -> WarmupSettings.parse("si", "on", 10, 60, 0))
                .getMessage().contains("SCHED_WARMUP inválido"));
        assertTrue(assertThrows(IllegalArgumentException.class, () -> WarmupSettings.parse("ON", "on", 10, 60, 0))
                .getMessage().contains("on u off"));
        assertTrue(assertThrows(IllegalArgumentException.class, () -> WarmupSettings.parse("on", "on", -1, 60, 0))
                .getMessage().contains("SCHED_WARMUP_MIN_S"));
        assertTrue(assertThrows(IllegalArgumentException.class, () -> WarmupSettings.parse("on", "on", 0, 0, 0))
                .getMessage().contains("SCHED_WARMUP_MAX_S"));
        assertTrue(assertThrows(IllegalArgumentException.class, () -> WarmupSettings.parse("on", "on", 61, 60, 0))
                .getMessage().contains("no puede superar"));
        assertTrue(assertThrows(IllegalArgumentException.class, () -> WarmupSettings.parse("on", "on", 1, 60, -2))
                .getMessage().contains("SCHED_WARMUP_THREADS"));
    }

    @Test
    void theHttpWarmupHasItsOwnSwitch() {
        assertTrue(WarmupSettings.parse("on", "on", 10, 60, 0).http());
        assertFalse(WarmupSettings.parse("on", "off", 10, 60, 0).http());
        assertTrue(assertThrows(IllegalArgumentException.class, () -> WarmupSettings.parse("on", "si", 10, 60, 0))
                .getMessage().contains("SCHED_WARMUP_HTTP inválido"));
    }

    @Test
    void theIngestRoleNeverWarmsUp() {
        EngineConfig config = new EngineConfig();
        assertFalse(config.warmupSettings(Role.INGEST, "on", "on", 10, 60, 0).enabled());
        assertTrue(config.warmupSettings(Role.QUERY, "on", "on", 10, 60, 0).enabled());
        assertTrue(config.warmupSettings(Role.ALL, "on", "on", 10, 60, 0).enabled());
        assertFalse(config.warmupSettings(Role.QUERY, "off", "on", 10, 60, 0).enabled());
        // Un valor inválido falla aunque el rol sea ingest: se detecta el error de escritura.
        assertThrows(IllegalArgumentException.class, () -> config.warmupSettings(Role.INGEST, "x", "on", 10, 60, 0));
    }
}
