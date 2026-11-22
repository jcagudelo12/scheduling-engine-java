package sched.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class PreferencesTest {

    private static TimeSlot slot(Weekday day, int start, int end) {
        return TimeSlot.of(day, start, end).orElseThrow();
    }

    private static Section section(TimeSlot... slots) {
        return new Section(new SectionId("S"), new CourseId("C"), List.of(slots), 30);
    }

    @Test
    void withoutPreferencesThePenaltyIsZero() {
        assertTrue(Preferences.NONE.isEmpty());
        assertEquals(0, Preferences.NONE.penaltyOf(section(slot(Weekday.MONDAY, 420, 540))));
    }

    @Test
    void contiguousSlotsDoNotCount() {
        Preferences avoid = new Preferences(List.of(slot(Weekday.MONDAY, 540, 600)));
        assertEquals(0, avoid.penaltyOf(section(slot(Weekday.MONDAY, 420, 540))));
        assertEquals(0, avoid.penaltyOf(section(slot(Weekday.MONDAY, 600, 660))));
    }

    @Test
    void partialOverlapCounts() {
        Preferences avoid = new Preferences(List.of(slot(Weekday.MONDAY, 500, 600)));
        assertEquals(1, avoid.penaltyOf(section(slot(Weekday.MONDAY, 420, 540))));
    }

    @Test
    void differentDaysDoNotCount() {
        Preferences avoid = new Preferences(List.of(slot(Weekday.TUESDAY, 420, 540)));
        assertEquals(0, avoid.penaltyOf(section(slot(Weekday.MONDAY, 420, 540))));
    }

    @Test
    void eachSectionSlotCountsOnceEvenIfItTouchesSeveralWindows() {
        Preferences avoid = new Preferences(List.of(
                slot(Weekday.MONDAY, 400, 450), slot(Weekday.MONDAY, 500, 560)));
        assertEquals(1, avoid.penaltyOf(section(slot(Weekday.MONDAY, 420, 540))));
    }

    @Test
    void countsEverySlotOfTheSectionThatFalls() {
        Preferences avoid = new Preferences(List.of(
                slot(Weekday.MONDAY, 400, 450), slot(Weekday.WEDNESDAY, 0, 1440)));
        assertEquals(2, avoid.penaltyOf(section(
                slot(Weekday.MONDAY, 420, 540),
                slot(Weekday.WEDNESDAY, 420, 540),
                slot(Weekday.FRIDAY, 420, 540))));
    }
}
