package sched.adapters.http;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import sched.application.ApplyLogEntry;
import sched.application.CatalogLogEntry;
import sched.application.CatalogReplica;
import sched.application.Readiness;
import sched.domain.CourseId;
import sched.domain.Section;
import sched.domain.SectionId;
import sched.domain.SectionSeats;
import sched.domain.TimeSlot;
import sched.domain.Weekday;

/** {@code /ready} sigue el estado actual de la réplica, no solo el arranque. */
class HealthControllerTest {

    private static CatalogLogEntry.Snapshot snapshot(long seq) {
        Section section = new Section(new SectionId("MAT-01"), new CourseId("MAT"),
                List.of(TimeSlot.of(Weekday.MONDAY, 420, 540).orElseThrow()), 30);
        return new CatalogLogEntry.Snapshot(seq, List.of(new SectionSeats(section, 5)));
    }

    @Test
    void readyFollowsReplicaStateAndRecovers() throws Exception {
        CatalogReplica replica = new CatalogReplica();
        Readiness readiness = new Readiness(replica);
        ApplyLogEntry apply = new ApplyLogEntry(replica);
        HealthController controller = new HealthController(readiness);

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, controller.ready().getStatusCode());

        readiness.markCaughtUp();
        apply.execute(snapshot(100));
        assertEquals(HttpStatus.OK, controller.ready().getStatusCode());

        apply.execute(new CatalogLogEntry.SyncLost());
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, controller.ready().getStatusCode());
        assertEquals(HttpStatus.OK, controller.health().getStatusCode());

        apply.execute(snapshot(110));
        assertEquals(HttpStatus.OK, controller.ready().getStatusCode());

        readiness.markBehind();
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, controller.ready().getStatusCode());
    }
}
