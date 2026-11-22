package sched.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Identificadores, contexto del estudiante, grupo y combinación: reglas de construcción y comparación. */
class ValueObjectsTest {

    private static Section section(String id, String course, Weekday day, int start, int end) {
        return new Section(new SectionId(id), new CourseId(course),
                List.of(TimeSlot.of(day, start, end).orElseThrow()), 30);
    }

    @Test
    void identifiersRejectNullCompareByValueAndPrintTheirValue() {
        assertThrows(NullPointerException.class, () -> new StudentId(null));
        assertThrows(NullPointerException.class, () -> new CourseId(null));
        assertThrows(NullPointerException.class, () -> new SectionId(null));

        assertTrue(new StudentId("a").compareTo(new StudentId("b")) < 0);
        assertTrue(new CourseId("MAT").compareTo(new CourseId("FIS")) > 0);
        assertEquals(0, new SectionId("X").compareTo(new SectionId("X")));
        assertEquals("est-1", new StudentId("est-1").toString());
        assertEquals("MAT", new CourseId("MAT").toString());
        assertEquals("MAT-01", new SectionId("MAT-01").toString());
        assertEquals(new CourseId("MAT"), new CourseId("MAT"));
    }

    @Test
    void studentContextDropsRepeatedCoursesKeepingOrderAndDefaultsThePreferences() {
        var context = new StudentContext(new StudentId("e"),
                List.of(new CourseId("B"), new CourseId("A"), new CourseId("B")), null);

        assertEquals(List.of(new CourseId("B"), new CourseId("A")), context.eligibleCourses());
        assertEquals(Preferences.NONE, context.preferences());
        assertEquals(Preferences.NONE, new StudentContext(new StudentId("e"), List.of()).preferences());
        assertThrows(UnsupportedOperationException.class, () -> context.eligibleCourses().add(new CourseId("C")));
    }

    @Test
    void sectionsClashOnlyWhenTheirSlotsOverlap() {
        var monday = section("A", "MAT", Weekday.MONDAY, 420, 540);
        var overlapping = section("B", "FIS", Weekday.MONDAY, 500, 600);
        var adjacent = section("C", "QUI", Weekday.MONDAY, 540, 600);
        var otherDay = section("D", "HIS", Weekday.TUESDAY, 420, 540);

        assertTrue(monday.clashesWith(overlapping));
        assertFalse(monday.clashesWith(adjacent));
        assertFalse(monday.clashesWith(otherDay));
    }

    @Test
    void aSectionKeepsAnImmutableCopyOfItsSlots() {
        var slots = new ArrayList<>(List.of(TimeSlot.of(Weekday.MONDAY, 0, 60).orElseThrow()));
        var section = new Section(new SectionId("A"), new CourseId("MAT"), slots, 10);

        slots.clear();

        assertEquals(1, section.slots().size());
        assertThrows(UnsupportedOperationException.class, () -> section.slots().clear());
    }

    @Test
    void aScheduleDetectsClashesBetweenItsSections() {
        var a = section("A", "MAT", Weekday.MONDAY, 420, 540);
        var b = section("B", "FIS", Weekday.MONDAY, 480, 600);
        var c = section("C", "QUI", Weekday.TUESDAY, 480, 600);

        assertTrue(new Schedule(List.of(a, b, c)).hasClashes());
        assertFalse(new Schedule(List.of(a, c)).hasClashes());
        assertFalse(new Schedule(List.of()).hasClashes());
        assertThrows(UnsupportedOperationException.class, () -> new Schedule(List.of(a)).sections().clear());
    }
}
