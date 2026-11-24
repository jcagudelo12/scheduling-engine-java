package sched.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import sched.application.ports.CatalogLog;
import sched.application.ports.EngineMetrics;
import sched.domain.ApplyOutcome;
import sched.domain.CatalogChange;
import sched.domain.CatalogEvent;
import sched.domain.CourseId;
import sched.domain.Section;
import sched.domain.SectionId;
import sched.domain.SectionSeats;
import sched.domain.TimeSlot;
import sched.domain.Weekday;

/** Los casos de uso de ingesta con un historial falso: orden, duplicados, huecos, fallos y mediciones. */
class IngestUseCasesTest {

    /** Historial que aplica cada entrada a la réplica, como el de memoria, y puede fallar a pedido. */
    private static final class FakeLog implements CatalogLog {
        final List<CatalogLogEntry> entries = new ArrayList<>();
        final ApplyLogEntry apply;
        boolean failing;

        FakeLog(ApplyLogEntry apply) {
            this.apply = apply;
        }

        @Override
        public void append(CatalogLogEntry entry) throws PortException {
            if (failing) {
                throw new PortException(PortException.Kind.UNAVAILABLE, "NATS caído");
            }
            entries.add(entry);
            try {
                apply.execute(entry);
            } catch (AppException e) {
                throw new AssertionError(e);
            }
        }
    }

    private final List<String> events = new ArrayList<>();
    private final EngineMetrics metrics = new EngineMetrics() {
        @Override
        public void ingestEvent(IngestOutcome outcome) {
            events.add(outcome.name());
        }

        @Override
        public void snapshotIngested(int sections) {
            events.add("SNAPSHOT:" + sections);
        }

        @Override
        public void syncLost() {
            events.add("SYNC_LOST");
        }
    };

    private final CatalogReplica replica = new CatalogReplica();
    private final FakeLog log = new FakeLog(new ApplyLogEntry(replica));
    private final IngestState state = new IngestState(replica);
    private final IngestCatalog load = new IngestCatalog(state, log, metrics);
    private final IngestCatalogEvent changes = new IngestCatalogEvent(state, log, metrics);

    private static Section section(String id) {
        return new Section(new SectionId(id), new CourseId("MAT"),
                List.of(TimeSlot.of(Weekday.MONDAY, 420, 480).orElseThrow()), 30);
    }

    private static CatalogEvent seats(long seq, int available) {
        return new CatalogEvent(seq, new CatalogChange.SeatsChanged(new SectionId("MAT-01"), available));
    }

    private void loadOne() throws Exception {
        assertEquals(1, load.execute(1, List.of(new SectionSeats(section("MAT-01"), 5))));
    }

    @Test
    void aSnapshotIsWrittenToTheLogAndMeasured() throws Exception {
        loadOne();

        assertEquals(1, log.entries.size());
        assertInstanceOf(CatalogLogEntry.Snapshot.class, log.entries.getFirst());
        assertEquals(List.of("SNAPSHOT:1"), events);
    }

    @Test
    void changesAreAppliedOnceAndADuplicateIsNotWrittenAgain() throws Exception {
        loadOne();

        assertEquals(ApplyOutcome.APPLIED, changes.execute(seats(2, 4)));
        assertEquals(ApplyOutcome.DUPLICATE, changes.execute(seats(2, 4)));

        assertEquals(2, log.entries.size());
        assertEquals(List.of("SNAPSHOT:1", "APPLIED", "DUPLICATE"), events);
    }

    @Test
    void aGapIsRejectedTheLogIsToldAndOnlyASnapshotRecovers() throws Exception {
        loadOne();

        var gap = assertThrows(AppException.SequenceGap.class, () -> changes.execute(seats(5, 1)));

        assertEquals(2, gap.expected());
        assertEquals(5, gap.received());
        assertInstanceOf(CatalogLogEntry.SyncLost.class, log.entries.getLast());
        assertEquals(List.of("SNAPSHOT:1", "GAP", "SYNC_LOST"), events);
        // Aun el seq que sí tocaba se rechaza hasta la carga completa.
        assertThrows(AppException.SequenceGap.class, () -> changes.execute(seats(2, 1)));
        load.execute(10, List.of(new SectionSeats(section("MAT-01"), 5)));
        assertEquals(ApplyOutcome.APPLIED, changes.execute(seats(11, 1)));
    }

    @Test
    void aLogFailureDoesNotConsumeTheSeqSoItCanBeResent() throws Exception {
        loadOne();
        log.failing = true;

        var failure = assertThrows(AppException.PortFailure.class, () -> changes.execute(seats(2, 4)));
        assertEquals(PortException.Kind.UNAVAILABLE, failure.kind());
        log.failing = false;

        assertEquals(ApplyOutcome.APPLIED, changes.execute(seats(2, 4)));
        assertEquals(List.of("SNAPSHOT:1", "FAILED", "APPLIED"), events);
    }

    @Test
    void aFailedSnapshotIsReportedAsAPortFailure() {
        log.failing = true;

        assertThrows(AppException.PortFailure.class, () -> load.execute(1, List.of(new SectionSeats(section("MAT-01"), 5))));
        assertTrue(events.isEmpty());
    }

    @Test
    void aLostConnectionTellsTheLogAndSurvivesALogFailure() throws Exception {
        loadOne();

        changes.connectionLost();
        assertInstanceOf(CatalogLogEntry.SyncLost.class, log.entries.getLast());
        assertTrue(replica.isStale());

        log.failing = true;
        changes.connectionLost();
        assertEquals(List.of("SNAPSHOT:1", "SYNC_LOST", "SYNC_LOST"), events);
    }

    @Test
    void changesBeforeAnySnapshotAreUnavailable() {
        assertThrows(AppException.CatalogUnavailable.class, () -> changes.execute(seats(1, 1)));
    }

    @Test
    void theDefaultConstructorsDoNotMeasureAnything() throws Exception {
        var plainLoad = new IngestCatalog(state, log);
        var plainChanges = new IngestCatalogEvent(state, log);

        plainLoad.execute(1, List.of(new SectionSeats(section("MAT-01"), 5)));
        assertEquals(ApplyOutcome.APPLIED, plainChanges.execute(seats(2, 3)));
        assertTrue(events.isEmpty());
    }
}
