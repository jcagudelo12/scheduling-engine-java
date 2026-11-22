package sched.adapters.inmemory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import sched.application.Alternatives;
import sched.application.Alternatives.Reason;
import sched.application.AppException;
import sched.application.ApplyLogEntry;
import sched.application.CatalogReplica;
import sched.application.IngestCatalog;
import sched.application.SuggestAlternatives;
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

/** Casos de uso de {@link SuggestAlternatives}: conservar la selección y cambiar solo lo necesario. */
class SuggestAlternativesTest {

    private static final CourseId MAT = new CourseId("MAT");
    private static final CourseId FIS = new CourseId("FIS");

    private IngestCatalog load;
    private SuggestAlternatives suggest;

    @BeforeEach
    void engine() {
        CatalogReplica replica = new CatalogReplica();
        InMemoryCatalogLog log = new InMemoryCatalogLog(new ApplyLogEntry(replica));
        load = new IngestCatalog(new sched.application.IngestState(replica), log);
        suggest = new SuggestAlternatives(new ForwardCheckingSolver(), replica, 10);
    }

    /** Grupo de dos horas (7:00 a 9:00) el día indicado. */
    private static SectionSeats group(String id, CourseId course, Weekday day, int seats) {
        Section section = new Section(new SectionId(id), course,
                List.of(TimeSlot.of(day, 420, 540).orElseThrow()), 30);
        return new SectionSeats(section, seats);
    }

    private static StudentContext student(Preferences preferences, CourseId... courses) {
        return new StudentContext(new StudentId("s1"), List.of(courses), preferences);
    }

    private static Map<CourseId, SectionId> selection(String mat, String fis) {
        return Map.of(MAT, new SectionId(mat), FIS, new SectionId(fis));
    }

    private static Preferences avoiding(Weekday day) {
        return new Preferences(List.of(TimeSlot.of(day, 420, 540).orElseThrow()));
    }

    private static List<String> ids(Alternatives.Alternative alternative) {
        return alternative.schedule().sections().stream().map(s -> s.id().value()).toList();
    }

    @Test
    void fullGroupChangesOnlyThatCourse() throws Exception {
        load.execute(1, List.of(
                group("MAT-01", MAT, Weekday.MONDAY, 0),
                group("MAT-02", MAT, Weekday.WEDNESDAY, 5),
                group("FIS-01", FIS, Weekday.TUESDAY, 5)));

        Alternatives result = suggest.execute(student(Preferences.NONE, MAT, FIS), selection("MAT-01", "FIS-01"));

        assertFalse(result.selectionValid());
        assertEquals(List.of(new Alternatives.Choice(MAT, new SectionId("MAT-01"))),
                result.conflicts().withoutSeats());
        Alternatives.Alternative best = result.alternatives().getFirst();
        assertEquals(1, best.distance());
        assertEquals(List.of("MAT-02", "FIS-01"), ids(best));
        assertEquals(List.of(new Alternatives.Change(MAT, new SectionId("MAT-01"), new SectionId("MAT-02"),
                Reason.NO_SEATS)), best.changes());
    }

    @Test
    void amongEqualDistanceTheLowestPenaltyWins() throws Exception {
        load.execute(1, List.of(
                group("MAT-01", MAT, Weekday.MONDAY, 0),
                group("MAT-02", MAT, Weekday.WEDNESDAY, 5),
                group("MAT-03", MAT, Weekday.THURSDAY, 5),
                group("FIS-01", FIS, Weekday.TUESDAY, 5)));

        Alternatives result = suggest.execute(
                student(avoiding(Weekday.WEDNESDAY), MAT, FIS), selection("MAT-01", "FIS-01"));

        List<Alternatives.Alternative> all = result.alternatives();
        assertEquals(List.of("MAT-03", "FIS-01"), ids(all.getFirst()));
        assertEquals(0, all.getFirst().penalty());
        assertEquals(List.of("MAT-02", "FIS-01"), ids(all.get(1)));
        assertEquals(1, all.get(1).penalty());
        assertTrue(all.stream().allMatch(a -> a.distance() == 1));
    }

    @Test
    void replacementThatForcesAnotherCourseIsRearranged() throws Exception {
        load.execute(1, List.of(
                group("MAT-01", MAT, Weekday.MONDAY, 0),
                group("MAT-02", MAT, Weekday.TUESDAY, 5),   // choca con FIS-01
                group("FIS-01", FIS, Weekday.TUESDAY, 5),
                group("FIS-02", FIS, Weekday.THURSDAY, 5)));

        Alternatives result = suggest.execute(student(Preferences.NONE, MAT, FIS), selection("MAT-01", "FIS-01"));

        Alternatives.Alternative only = result.alternatives().getFirst();
        assertEquals(1, result.alternatives().size());
        assertEquals(2, only.distance());
        assertEquals(List.of(
                new Alternatives.Change(MAT, new SectionId("MAT-01"), new SectionId("MAT-02"), Reason.NO_SEATS),
                new Alternatives.Change(FIS, new SectionId("FIS-01"), new SectionId("FIS-02"), Reason.REARRANGED)),
                only.changes());
    }

    @Test
    void distanceDominatesPenalty() throws Exception {
        load.execute(1, List.of(
                group("MAT-01", MAT, Weekday.MONDAY, 0),
                group("MAT-02", MAT, Weekday.WEDNESDAY, 5),  // penalización 1
                group("MAT-03", MAT, Weekday.TUESDAY, 5),    // choca con FIS-01
                group("FIS-01", FIS, Weekday.TUESDAY, 5),
                group("FIS-02", FIS, Weekday.THURSDAY, 5)));

        Alternatives result = suggest.execute(
                student(avoiding(Weekday.WEDNESDAY), MAT, FIS), selection("MAT-01", "FIS-01"));

        List<Alternatives.Alternative> all = result.alternatives();
        assertEquals(List.of("MAT-02", "FIS-01"), ids(all.get(0)));
        assertEquals(1, all.get(0).distance());
        assertEquals(1, all.get(0).penalty());
        assertEquals(List.of("MAT-03", "FIS-02"), ids(all.get(1)));
        assertEquals(2, all.get(1).distance());
        assertEquals(0, all.get(1).penalty());
    }

    @Test
    void validSelectionComesFirstWithDistanceZero() throws Exception {
        load.execute(1, List.of(
                group("MAT-01", MAT, Weekday.MONDAY, 5),
                group("MAT-02", MAT, Weekday.WEDNESDAY, 5),
                group("FIS-01", FIS, Weekday.TUESDAY, 5)));

        Alternatives result = suggest.execute(
                student(avoiding(Weekday.MONDAY), MAT, FIS), selection("MAT-01", "FIS-01"));

        assertTrue(result.selectionValid());
        assertTrue(result.conflicts().withoutSeats().isEmpty());
        assertTrue(result.conflicts().clashes().isEmpty());
        Alternatives.Alternative first = result.alternatives().getFirst();
        assertEquals(0, first.distance());
        assertEquals(1, first.penalty());   // las preferencias no desplazan la selección
        assertTrue(first.changes().isEmpty());
        assertEquals(1, result.alternatives().get(1).distance());
    }

    @Test
    void clashInTheSelectionIsReportedAndExplained() throws Exception {
        load.execute(1, List.of(
                group("MAT-01", MAT, Weekday.MONDAY, 5),
                group("MAT-02", MAT, Weekday.WEDNESDAY, 5),
                group("FIS-01", FIS, Weekday.MONDAY, 5),
                group("FIS-02", FIS, Weekday.TUESDAY, 5)));

        Alternatives result = suggest.execute(student(Preferences.NONE, MAT, FIS), selection("MAT-01", "FIS-01"));

        assertFalse(result.selectionValid());
        assertEquals(1, result.conflicts().clashes().size());
        assertEquals(new Alternatives.Clash(
                new Alternatives.Choice(MAT, new SectionId("MAT-01")),
                new Alternatives.Choice(FIS, new SectionId("FIS-01"))), result.conflicts().clashes().getFirst());
        Alternatives.Alternative best = result.alternatives().getFirst();
        assertEquals(1, best.distance());
        assertEquals(Reason.CLASH, best.changes().getFirst().reason());
    }

    @Test
    void courseWithoutChoiceIsFreeAndNotCounted() throws Exception {
        load.execute(1, List.of(
                group("MAT-01", MAT, Weekday.MONDAY, 5),
                group("FIS-01", FIS, Weekday.TUESDAY, 5),
                group("FIS-02", FIS, Weekday.THURSDAY, 5)));

        Alternatives result = suggest.execute(
                student(Preferences.NONE, MAT, FIS), Map.of(MAT, new SectionId("MAT-01")));

        assertFalse(result.selectionValid());
        Alternatives.Alternative first = result.alternatives().getFirst();
        assertEquals(0, first.distance());
        assertEquals(1, first.changes().size());
        assertEquals(FIS, first.changes().getFirst().course());
        assertNull(first.changes().getFirst().from());
        assertEquals(Reason.NOT_SELECTED, first.changes().getFirst().reason());
        assertEquals(2, result.alternatives().size());
    }

    @Test
    void groupMissingFromTheCatalogCountsAsWithoutSeats() throws Exception {
        load.execute(1, List.of(
                group("MAT-01", MAT, Weekday.MONDAY, 5),
                group("FIS-01", FIS, Weekday.TUESDAY, 5)));

        Alternatives result = suggest.execute(student(Preferences.NONE, MAT, FIS), selection("MAT-99", "FIS-01"));

        assertEquals(List.of(new Alternatives.Choice(MAT, new SectionId("MAT-99"))),
                result.conflicts().withoutSeats());
        assertEquals(Reason.NO_SEATS, result.alternatives().getFirst().changes().getFirst().reason());
    }

    @Test
    void noSolutionGivesAnEmptyList() throws Exception {
        load.execute(1, List.of(
                group("MAT-01", MAT, Weekday.MONDAY, 5),
                group("FIS-01", FIS, Weekday.TUESDAY, 0)));

        Alternatives result = suggest.execute(student(Preferences.NONE, MAT, FIS), selection("MAT-01", "FIS-01"));

        assertTrue(result.alternatives().isEmpty());
        assertFalse(result.selectionValid());
    }

    @Test
    void exceedingTheCreditLimitIsRefused() throws Exception {
        load.execute(1, List.of(group("MAT-01", MAT, Weekday.MONDAY, 5), group("FIS-01", FIS, Weekday.TUESDAY, 5)),
                List.of(new Course(MAT, 4), new Course(FIS, 3)), new EnrollmentRules(6));

        var error = assertThrows(AppException.CreditLimitExceeded.class,
                () -> suggest.execute(student(Preferences.NONE, MAT, FIS), selection("MAT-01", "FIS-01")));
        assertEquals(7, error.totalCredits());
    }

    @Test
    void selectionForAnUnrequestedCourseIsInvalid() throws Exception {
        load.execute(1, List.of(group("MAT-01", MAT, Weekday.MONDAY, 5)));

        assertThrows(AppException.InvalidSelection.class,
                () -> suggest.execute(student(Preferences.NONE, MAT), selection("MAT-01", "FIS-01")));
    }

    @Test
    void sectionOfAnotherCourseIsInvalid() throws Exception {
        load.execute(1, List.of(
                group("MAT-01", MAT, Weekday.MONDAY, 5), group("FIS-01", FIS, Weekday.TUESDAY, 5)));

        assertThrows(AppException.InvalidSelection.class,
                () -> suggest.execute(student(Preferences.NONE, MAT, FIS), selection("FIS-01", "FIS-01")));
    }
}
