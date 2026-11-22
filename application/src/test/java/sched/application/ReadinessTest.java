package sched.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import sched.domain.CatalogChange;
import sched.domain.CatalogEvent;
import sched.domain.CourseId;
import sched.domain.Section;
import sched.domain.SectionId;
import sched.domain.SectionSeats;
import sched.domain.TimeSlot;
import sched.domain.Weekday;

/** {@code Readiness} refleja el estado actual, y la réplica no se "cura" tras un hueco. */
class ReadinessTest {

    private CatalogReplica replica;
    private Readiness readiness;
    private ApplyLogEntry apply;

    private static CatalogLogEntry.Snapshot snapshot(long seq) {
        Section section = new Section(new SectionId("MAT-01"), new CourseId("MAT"),
                List.of(TimeSlot.of(Weekday.MONDAY, 420, 540).orElseThrow()), 30);
        return new CatalogLogEntry.Snapshot(seq, List.of(new SectionSeats(section, 5)));
    }

    private static CatalogLogEntry.Change change(long seq) {
        return new CatalogLogEntry.Change(
                new CatalogEvent(seq, new CatalogChange.SeatsChanged(new SectionId("MAT-01"), 1)));
    }

    @BeforeEach
    void setUp() {
        replica = new CatalogReplica();
        readiness = new Readiness(replica);
        apply = new ApplyLogEntry(replica);
    }

    @Test
    void notReadyWithoutSnapshotOrFollower() throws Exception {
        assertFalse(readiness.isReady());
        readiness.markCaughtUp();
        assertFalse(readiness.isReady(), "sin catálogo cargado");

        apply.execute(snapshot(100));
        assertTrue(readiness.isReady());
    }

    @Test
    void notReadyWhenFollowerDisconnects() throws Exception {
        apply.execute(snapshot(100));
        assertFalse(readiness.isReady());
        readiness.markCaughtUp();
        assertTrue(readiness.isReady());

        readiness.markBehind();
        assertFalse(readiness.isReady());
        assertFalse(readiness.isCaughtUp());
    }

    @Test
    void syncLostMakesItNotReadyUntilNewSnapshot() throws Exception {
        apply.execute(snapshot(100));
        readiness.markCaughtUp();

        apply.execute(new CatalogLogEntry.SyncLost());
        assertFalse(readiness.isReady());

        apply.execute(snapshot(110));
        assertTrue(readiness.isReady());
    }

    @Test
    void gapMakesItNotReadyUntilNewSnapshot() throws Exception {
        apply.execute(snapshot(100));
        readiness.markCaughtUp();

        assertThrows(AppException.SequenceGap.class, () -> apply.execute(change(105)));
        assertFalse(readiness.isReady());
        assertTrue(replica.isStale());

        apply.execute(snapshot(105));
        assertTrue(readiness.isReady());
    }

    @Test
    void eventsAfterAGapDoNotMakeTheReplicaFreshAgain() throws Exception {
        apply.execute(snapshot(100));
        readiness.markCaughtUp();

        assertThrows(AppException.SequenceGap.class, () -> apply.execute(change(102)));
        // El faltante llega tarde: no se aplica y la réplica sigue desactualizada.
        assertThrows(AppException.SequenceGap.class, () -> apply.execute(change(101)));
        assertTrue(replica.isStale());
        assertFalse(readiness.isReady());
        long seq = replica.read((catalog, stale) -> catalog.seq());
        assertEquals(100L, seq);
    }

    @Test
    void connectionLossWithoutGapRecoversWithNextEvent() throws Exception {
        apply.execute(snapshot(100));
        readiness.markCaughtUp();
        apply.execute(new CatalogLogEntry.SyncLost());
        assertFalse(readiness.isReady());

        apply.execute(change(101));
        assertTrue(readiness.isReady());
    }
}
