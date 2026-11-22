package sched.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import sched.application.ports.EngineMetrics;
import sched.domain.Course;
import sched.domain.CourseId;
import sched.domain.EnrollmentRules;
import sched.domain.Preferences;
import sched.domain.Section;
import sched.domain.SectionId;
import sched.domain.SectionSeats;
import sched.domain.StudentContext;
import sched.domain.StudentId;
import sched.domain.TimeSlot;
import sched.domain.Weekday;
import sched.solver.ForwardCheckingSolver;

/** El caso de uso de combinaciones sin adaptadores: réplica en memoria y el solver real. */
class GenerateCombinationsTest {

    private final CatalogReplica replica = new CatalogReplica();
    private final List<String> reported = new ArrayList<>();
    private final EngineMetrics metrics = new EngineMetrics() {
        @Override
        public void searchFinished(Operation operation, Duration elapsed, int results, boolean complete, long nodes) {
            reported.add(operation + " results=" + results + " complete=" + complete + " nodes>0=" + (nodes > 0));
        }
    };

    private static Section section(String id, String course, Weekday day, int start) {
        return new Section(new SectionId(id), new CourseId(course),
                List.of(TimeSlot.of(day, start, start + 60).orElseThrow()), 30);
    }

    @BeforeEach
    void loadCatalog() throws Exception {
        new ApplyLogEntry(replica).execute(new CatalogLogEntry.Snapshot(7, List.of(
                new SectionSeats(section("MAT-01", "MAT", Weekday.MONDAY, 420), 5),
                new SectionSeats(section("MAT-02", "MAT", Weekday.TUESDAY, 420), 5),
                new SectionSeats(section("FIS-01", "FIS", Weekday.MONDAY, 420), 5),
                new SectionSeats(section("FIS-02", "FIS", Weekday.WEDNESDAY, 420), 0)),
                List.of(new Course(new CourseId("MAT"), 4), new Course(new CourseId("FIS"), 4)),
                new EnrollmentRules(8)));
    }

    private static StudentContext student(Preferences preferences, String... courses) {
        return new StudentContext(new StudentId("est-1"),
                java.util.Arrays.stream(courses).map(CourseId::new).toList(), preferences);
    }

    @Test
    void onlySectionsWithSeatsAreCombinedAndTheCatalogStateIsReported() throws Exception {
        var generate = new GenerateCombinations(new ForwardCheckingSolver(), replica, 10, Duration.ofSeconds(5), metrics);

        Combinations result = generate.execute(student(Preferences.NONE, "MAT", "FIS"));

        // FIS-02 no tiene cupo y FIS-01 se cruza con MAT-01: solo (MAT-02, FIS-01).
        assertEquals(1, result.schedules().size());
        assertEquals(List.of("MAT-02", "FIS-01"),
                result.schedules().getFirst().schedule().sections().stream().map(s -> s.id().value()).toList());
        assertEquals(7, result.catalogSeq());
        assertFalse(result.stale());
        assertTrue(result.complete());
        assertEquals(8, result.totalCredits());
        assertEquals(8, result.maxCredits());
        assertEquals(List.of("COMBINATIONS results=1 complete=true nodes>0=true"), reported);
    }

    @Test
    void creditsAboveTheLimitAreRejectedBeforeSearching() {
        var generate = new GenerateCombinations(new ForwardCheckingSolver(), replica, 10, null, metrics);
        replicaWithLimit(4);

        var error = assertThrows(AppException.CreditLimitExceeded.class,
                () -> generate.execute(student(Preferences.NONE, "MAT", "FIS")));

        assertEquals(8, error.totalCredits());
        assertEquals(4, error.maxCredits());
        assertTrue(reported.isEmpty(), "no se hizo ninguna búsqueda");
    }

    private void replicaWithLimit(int max) {
        try {
            new ApplyLogEntry(replica).execute(new CatalogLogEntry.Snapshot(8, List.of(
                    new SectionSeats(section("MAT-01", "MAT", Weekday.MONDAY, 420), 5)),
                    List.of(new Course(new CourseId("MAT"), 4), new Course(new CourseId("FIS"), 4)),
                    new EnrollmentRules(max)));
        } catch (AppException e) {
            throw new AssertionError(e);
        }
    }

    @Test
    void aTimeLimitOfZeroCutsTheSearchAndSaysSo() throws Exception {
        var generate = new GenerateCombinations(new ForwardCheckingSolver(), replica, 10, Duration.ZERO, metrics);

        Combinations result = generate.execute(student(Preferences.NONE, "MAT", "FIS"));

        assertFalse(result.complete());
        assertTrue(reported.getFirst().contains("complete=false"));
    }

    @Test
    void preferencesOrderTheResultsByPenalty() throws Exception {
        new ApplyLogEntry(replica).execute(new CatalogLogEntry.Snapshot(9, List.of(
                new SectionSeats(section("MAT-01", "MAT", Weekday.MONDAY, 420), 5),
                new SectionSeats(section("MAT-02", "MAT", Weekday.TUESDAY, 420), 5))));
        var avoidMonday = new Preferences(List.of(TimeSlot.of(Weekday.MONDAY, 0, 1440).orElseThrow()));
        var generate = new GenerateCombinations(new ForwardCheckingSolver(), replica, 10);

        Combinations result = generate.execute(student(avoidMonday, "MAT"));

        assertEquals("MAT-02", result.schedules().getFirst().schedule().sections().getFirst().id().value());
        assertEquals(0, result.schedules().getFirst().penalty());
        assertTrue(result.schedules().get(1).penalty() > 0);
    }

    @Test
    void withoutACatalogTheUseCaseIsUnavailableAndAStaleReplicaIsReported() throws Exception {
        var empty = new GenerateCombinations(new ForwardCheckingSolver(), new CatalogReplica(), 10);
        assertThrows(AppException.CatalogUnavailable.class, () -> empty.execute(student(Preferences.NONE, "MAT")));

        new ApplyLogEntry(replica).execute(new CatalogLogEntry.SyncLost());
        var generate = new GenerateCombinations(new ForwardCheckingSolver(), replica, 10);
        assertTrue(generate.execute(student(Preferences.NONE, "MAT")).stale());
    }
}
