package sched.solver;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;
import sched.domain.CourseId;
import sched.domain.Section;
import sched.domain.SectionId;
import sched.domain.TimeSlot;
import sched.domain.Weekday;

class SlotBitmapsTest {

    private static Section section(String id, TimeSlot... slots) {
        return new Section(new SectionId(id), new CourseId("C"), List.of(slots), 30);
    }

    private static TimeSlot slot(Weekday day, int start, int end) {
        return TimeSlot.of(day, start, end).orElseThrow();
    }

    @Test
    void contiguousSlotsDoNotClash() {
        SlotBitmaps b = SlotBitmaps.of(List.of(
                section("A", slot(Weekday.MONDAY, 480, 600)),
                section("B", slot(Weekday.MONDAY, 600, 720))));
        assertFalse(b.clash(0, 1));
    }

    @Test
    void overlappingSlotsClash() {
        SlotBitmaps b = SlotBitmaps.of(List.of(
                section("A", slot(Weekday.MONDAY, 480, 600)),
                section("B", slot(Weekday.MONDAY, 599, 720))));
        assertTrue(b.clash(0, 1));
    }

    @Test
    void differentDaysDoNotClash() {
        SlotBitmaps b = SlotBitmaps.of(List.of(
                section("A", slot(Weekday.MONDAY, 480, 600)),
                section("B", slot(Weekday.TUESDAY, 480, 600))));
        assertFalse(b.clash(0, 1));
    }

    @Test
    void midnightBoundaryBetweenDaysDoesNotClash() {
        SlotBitmaps b = SlotBitmaps.of(List.of(
                section("A", slot(Weekday.MONDAY, 1380, 1440)),
                section("B", slot(Weekday.TUESDAY, 0, 60))));
        assertFalse(b.clash(0, 1));
    }

    @Test
    void containedSlotClashes() {
        SlotBitmaps b = SlotBitmaps.of(List.of(
                section("A", slot(Weekday.FRIDAY, 360, 900)),
                section("B", slot(Weekday.FRIDAY, 600, 660))));
        assertTrue(b.clash(0, 1));
    }

    @Test
    void emptyInputIsSupported() {
        assertEquals(0, SlotBitmaps.of(List.of()).size());
    }

    @Test
    void agreesWithTimeSlotOverlapsOnRandomSections() {
        Random random = new Random(42);
        for (int round = 0; round < 200; round++) {
            List<Section> sections = new ArrayList<>();
            for (int i = 0; i < 8; i++) {
                List<TimeSlot> slots = new ArrayList<>();
                int count = 1 + random.nextInt(3);
                for (int j = 0; j < count; j++) {
                    int start = random.nextInt(24 * 60 - 1);
                    int end = start + 1 + random.nextInt(Math.min(300, 24 * 60 - start));
                    slots.add(slot(Weekday.values()[random.nextInt(3)], start, end));
                }
                sections.add(new Section(new SectionId("S" + i), new CourseId("C"), slots, 30));
            }
            SlotBitmaps bitmaps = SlotBitmaps.of(sections);
            for (int a = 0; a < sections.size(); a++) {
                for (int b = 0; b < sections.size(); b++) {
                    if (a != b) {
                        assertEquals(sections.get(a).clashesWith(sections.get(b)), bitmaps.clash(a, b));
                    }
                }
            }
        }
    }
}
