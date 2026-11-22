package sched.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import sched.application.ports.CatalogLog;
import sched.domain.ApplyOutcome;
import sched.domain.CatalogChange;
import sched.domain.CatalogEvent;
import sched.domain.CourseId;
import sched.domain.Section;
import sched.domain.SectionId;
import sched.domain.SectionSeats;
import sched.domain.TimeSlot;
import sched.domain.Weekday;

/** Fiabilidad de la ingesta: fallos de escritura y flujos concurrentes. */
class IngestReliabilityTest {

    /** Historial falso: puede fallar las primeras escrituras y demorar las demás. */
    private static final class FakeLog implements CatalogLog {
        final List<CatalogLogEntry> entries = new ArrayList<>();
        final AtomicInteger failures = new AtomicInteger();
        final CountDownLatch firstAppendStarted = new CountDownLatch(1);
        volatile long delayMillis;

        @Override
        public void append(CatalogLogEntry entry) throws PortException {
            if (failures.getAndDecrement() > 0) {
                throw new PortException(PortException.Kind.UNAVAILABLE, "NATS no disponible");
            }
            firstAppendStarted.countDown();
            if (delayMillis > 0) {
                try {
                    Thread.sleep(delayMillis);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            synchronized (entries) {
                entries.add(entry);
            }
        }

        List<Long> changeSeqs() {
            synchronized (entries) {
                return entries.stream()
                        .filter(CatalogLogEntry.Change.class::isInstance)
                        .map(e -> ((CatalogLogEntry.Change) e).event().seq())
                        .toList();
            }
        }
    }

    private FakeLog log;
    private IngestCatalogEvent events;

    private static CatalogEvent seats(long seq) {
        return new CatalogEvent(seq, new CatalogChange.SeatsChanged(new SectionId("MAT-01"), 1));
    }

    @BeforeEach
    void setUp() throws Exception {
        CatalogReplica replica = new CatalogReplica();
        Section section = new Section(new SectionId("MAT-01"), new CourseId("MAT"),
                List.of(TimeSlot.of(Weekday.MONDAY, 420, 540).orElseThrow()), 30);
        new ApplyLogEntry(replica)
                .execute(new CatalogLogEntry.Snapshot(100, List.of(new SectionSeats(section, 5))));
        log = new FakeLog();
        events = new IngestCatalogEvent(new IngestState(replica), log);
    }

    @Test
    void failedWriteDoesNotConsumeSeqAndResendIsWritten() throws Exception {
        log.failures.set(1);

        assertThrows(AppException.PortFailure.class, () -> events.execute(seats(101)));
        assertTrue(log.changeSeqs().isEmpty());

        // El reenvío del mismo seq se escribe de verdad (no es un duplicado falso).
        assertEquals(ApplyOutcome.APPLIED, events.execute(seats(101)));
        assertEquals(List.of(101L), log.changeSeqs());

        // Ahora sí es un duplicado y no se escribe de nuevo.
        assertEquals(ApplyOutcome.DUPLICATE, events.execute(seats(101)));
        assertEquals(List.of(101L), log.changeSeqs());
        assertEquals(ApplyOutcome.APPLIED, events.execute(seats(102)));
    }

    @Test
    void concurrentStreamsWriteInSeqOrderWithoutLosses() throws Exception {
        log.delayMillis = 200;
        AtomicInteger failed = new AtomicInteger();
        Thread first = Thread.ofPlatform().start(() -> send(101, failed));
        // El segundo flujo llega cuando el primero ya está escribiendo (lento).
        assertTrue(log.firstAppendStarted.await(5, TimeUnit.SECONDS));
        Thread second = Thread.ofPlatform().start(() -> send(102, failed));
        first.join(5000);
        second.join(5000);

        assertEquals(0, failed.get());
        assertEquals(List.of(101L, 102L), log.changeSeqs());
    }

    private void send(long seq, AtomicInteger failed) {
        try {
            events.execute(seats(seq));
        } catch (AppException e) {
            failed.incrementAndGet();
        }
    }
}
