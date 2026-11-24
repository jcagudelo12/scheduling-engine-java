package sched.simulation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class SeatStormTest {

    private record Sent(long seq, String section, int available, long at) {
    }

    @Test
    void sendsEachSectionAtTheScheduledTimeWithConsecutiveSeqs() throws Exception {
        long[] clock = {1_000};
        List<Sent> sent = new ArrayList<>();

        SeatStorm.Result result = SeatStorm.run(List.of("A", "B", "C", "D"), 10, 1_000, 5, 1_000,
                (seq, section, available) -> sent.add(new Sent(seq, section, available, clock[0])),
                () -> clock[0], ms -> clock[0] += ms);

        assertEquals(4, result.sent());
        assertEquals(0, result.maxLagMs());
        assertEquals(List.of(5L, 6L, 7L, 8L), sent.stream().map(Sent::seq).toList());
        assertEquals(List.of("A", "B", "C", "D"), sent.stream().map(Sent::section).toList());
        assertTrue(sent.stream().allMatch(s -> s.available() == 0));
        // 10 eventos por segundo: uno cada 100 ms desde el inicio programado.
        assertEquals(List.of(1_000L, 1_100L, 1_200L, 1_300L), sent.stream().map(Sent::at).toList());
    }

    @Test
    void stopsAtTheDurationEvenIfSectionsRemain() throws Exception {
        long[] clock = {0};
        List<Sent> sent = new ArrayList<>();

        SeatStorm.Result result = SeatStorm.run(List.of("A", "B", "C", "D", "E", "F"), 2, 2_000, 2, 0,
                (seq, section, available) -> sent.add(new Sent(seq, section, available, clock[0])),
                () -> clock[0], ms -> clock[0] += ms);

        assertEquals(4, result.sent());
    }

    @Test
    void reportsTheLagWhenTheSenderIsSlow() throws Exception {
        long[] clock = {0};

        SeatStorm.Result result = SeatStorm.run(List.of("A", "B", "C"), 10, 1_000, 2, 0,
                (seq, section, available) -> clock[0] += 250, () -> clock[0], ms -> clock[0] += ms);

        // El 1.º sale a 0 y tarda 250 ms; el 2.º estaba previsto a 100 (retraso 150) y el 3.º a 200.
        assertEquals(3, result.sent());
        assertEquals(300, result.maxLagMs());
    }

    @Test
    void rejectsANonPositiveRate() {
        assertThrows(IllegalArgumentException.class,
                () -> SeatStorm.run(List.of("A"), 0, 1_000, 2, 0, (seq, section, available) -> {
                }));
    }
}
