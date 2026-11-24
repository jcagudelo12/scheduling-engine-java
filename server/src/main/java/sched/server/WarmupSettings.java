package sched.server;

import java.time.Duration;

/**
 * Configuración del calentamiento de la JVM antes de declarar la instancia lista
 * ({@code SCHED_WARMUP*}).
 *
 * @param enabled {@code SCHED_WARMUP=on}
 * @param http {@code SCHED_WARMUP_HTTP=on}: además, solicitudes {@code GET /health} a {@code localhost} que calientan Tomcat y Spring MVC
 * @param min tiempo mínimo de calentamiento: antes de esto no se evalúa el criterio de parada
 * @param max tope duro: pase lo que pase, a los {@code max} segundos se declara calentada
 * @param threads hilos de calentamiento; 0 = uno por CPU disponible
 */
record WarmupSettings(boolean enabled, boolean http, Duration min, Duration max, int threads) {

    static final WarmupSettings OFF = new WarmupSettings(false, false, Duration.ZERO, Duration.ofSeconds(1), 0);

    /** @throws IllegalArgumentException con un mensaje claro si algún valor no es válido */
    static WarmupSettings parse(String mode, String httpMode, long minSeconds, long maxSeconds, int threads) {
        boolean enabled = switch (mode) {
            case "on" -> true;
            case "off" -> false;
            default -> throw new IllegalArgumentException("SCHED_WARMUP inválido: '" + mode + "' (use on u off)");
        };
        boolean http = switch (httpMode) {
            case "on" -> true;
            case "off" -> false;
            default -> throw new IllegalArgumentException(
                    "SCHED_WARMUP_HTTP inválido: '" + httpMode + "' (use on u off)");
        };
        if (minSeconds < 0) {
            throw new IllegalArgumentException("SCHED_WARMUP_MIN_S no puede ser negativo: " + minSeconds);
        }
        if (maxSeconds < 1) {
            throw new IllegalArgumentException("SCHED_WARMUP_MAX_S debe ser al menos 1: " + maxSeconds);
        }
        if (minSeconds > maxSeconds) {
            throw new IllegalArgumentException("SCHED_WARMUP_MIN_S (" + minSeconds
                    + ") no puede superar SCHED_WARMUP_MAX_S (" + maxSeconds + ")");
        }
        if (threads < 0) {
            throw new IllegalArgumentException("SCHED_WARMUP_THREADS no puede ser negativo: " + threads);
        }
        return new WarmupSettings(enabled, http, Duration.ofSeconds(minSeconds), Duration.ofSeconds(maxSeconds), threads);
    }

    int effectiveThreads() {
        return threads > 0 ? threads : Math.max(1, Runtime.getRuntime().availableProcessors());
    }
}
