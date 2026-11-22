package sched.simulation;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.LongConsumer;
import java.util.function.LongSupplier;

/**
 * Ráfaga de eventos de cupo del pico de matrícula: vacía ({@code available = 0}) los grupos de
 * una lista, en orden, a un ritmo fijo, para medir E1 (cuánto tarda el motor en dejar de ofrecer
 * un grupo lleno) mientras corre la carga de consultas.
 *
 * <p>El evento {@code i} se envía en {@code inicio + i / ritmo}: quien mide (k6) calcula con la
 * misma fórmula cuándo se llenó cada grupo, sin hablar con este proceso.
 */
public final class SeatStorm {

    /** Quién envía los eventos (el stream gRPC real o un doble en las pruebas). */
    @FunctionalInterface
    public interface Sender {
        void send(long seq, String sectionId, int available) throws Exception;
    }

    /** Lo enviado y el mayor retraso respecto a la hora programada de un evento. */
    public record Result(int sent, long maxLagMs) {
    }

    private SeatStorm() {
    }

    /** Un grupo por línea; las líneas vacías se ignoran. */
    public static List<String> readSections(Path file) throws IOException {
        return Files.readAllLines(file).stream().map(String::strip).filter(l -> !l.isEmpty()).toList();
    }

    /**
     * Envía hasta {@code ratePerSecond * durationMs / 1000} eventos (o todos los grupos de la lista,
     * si son menos), el primero con {@code firstSeq} y los demás consecutivos. Si {@code startAtMs}
     * es futuro, espera hasta esa hora; si ya pasó, empieza de inmediato.
     */
    public static Result run(List<String> sections, double ratePerSecond, long durationMs, long firstSeq,
                             long startAtMs, Sender sender) throws Exception {
        return run(sections, ratePerSecond, durationMs, firstSeq, startAtMs, sender, System::currentTimeMillis,
                ms -> {
                    try {
                        Thread.sleep(ms);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(e);
                    }
                });
    }

    static Result run(List<String> sections, double ratePerSecond, long durationMs, long firstSeq, long startAtMs,
                      Sender sender, LongSupplier nowMs, LongConsumer sleepMs) throws Exception {
        if (ratePerSecond <= 0) {
            throw new IllegalArgumentException("--rate debe ser mayor que 0");
        }
        long total = Math.min(sections.size(), (long) Math.floor(ratePerSecond * durationMs / 1000.0));
        long start = Math.max(startAtMs, nowMs.getAsLong());
        long maxLag = 0;
        int sent = 0;
        for (int i = 0; i < total; i++) {
            long due = start + Math.round(i * 1000.0 / ratePerSecond);
            long wait = due - nowMs.getAsLong();
            if (wait > 0) {
                sleepMs.accept(wait);
            }
            maxLag = Math.max(maxLag, Math.max(0, nowMs.getAsLong() - due));
            sender.send(firstSeq + i, sections.get(i), 0);
            sent++;
        }
        return new Result(sent, maxLag);
    }
}
