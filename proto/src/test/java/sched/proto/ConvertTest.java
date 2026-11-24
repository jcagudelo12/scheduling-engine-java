package sched.proto;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import org.junit.jupiter.api.Test;
import sched.domain.CatalogChange;
import sched.domain.CatalogEvent;
import sched.domain.Course;
import sched.domain.CourseId;
import sched.domain.EnrollmentRules;
import sched.domain.Section;
import sched.domain.SectionId;
import sched.domain.SectionSeats;
import sched.domain.TimeSlot;
import sched.domain.Weekday;

class ConvertTest {

    @Test
    void sectionRoundTrip() throws Exception {
        Section original = new Section(new SectionId("MAT-01"), new CourseId("MAT"),
                List.of(TimeSlot.of(Weekday.FRIDAY, 420, 540).orElseThrow()), 30);
        assertEquals(new SectionSeats(original, 7), Convert.section(Convert.sectionToPb(original, 7)));
    }

    @Test
    void eventRoundTrip() throws Exception {
        CatalogEvent original = new CatalogEvent(42, new CatalogChange.SeatsChanged(new SectionId("MAT-01"), 3));
        assertEquals(original, Convert.catalogEvent(Convert.catalogEventToPb(original)));
    }

    @Test
    void courseRoundTrip() throws Exception {
        Course original = new Course(new CourseId("MAT101"), 4);
        assertEquals(original, Convert.course(Convert.courseToPb(original)));
    }

    @Test
    void rulesRoundTrip() throws Exception {
        assertEquals(new EnrollmentRules(18), Convert.rules(Convert.rulesToPb(new EnrollmentRules(18))));
        assertEquals(EnrollmentRules.NONE, Convert.rules(Convert.rulesToPb(EnrollmentRules.NONE)));
    }

    @Test
    void courseWithoutIdIsRejected() {
        var pb = sched.proto.institution.v1.Course.newBuilder().setCredits(3).build();
        assertThrows(Convert.ConversionException.class, () -> Convert.course(pb));
    }

    @Test
    void outOfRangeCreditsAreRejected() {
        // uint32 por encima de Integer.MAX_VALUE llega negativo a Java.
        var course = sched.proto.institution.v1.Course.newBuilder().setCourseId("MAT101").setCredits(-1).build();
        var rules = sched.proto.institution.v1.EnrollmentRules.newBuilder().setMaxCredits(-1).build();
        assertThrows(Convert.ConversionException.class, () -> Convert.course(course));
        assertThrows(Convert.ConversionException.class, () -> Convert.rules(rules));
    }
}
