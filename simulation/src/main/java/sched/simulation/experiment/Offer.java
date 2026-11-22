package sched.simulation.experiment;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import sched.domain.Course;
import sched.domain.CourseId;
import sched.domain.EnrollmentRules;
import sched.domain.Section;
import sched.domain.SectionSeats;

/** Oferta del periodo: grupos con su cupo inicial, créditos y reglas. */
public record Offer(List<SectionSeats> sections, List<Course> courses, EnrollmentRules rules) {

    /** Grupos de una asignatura, ordenados por id. */
    Map<CourseId, List<Section>> sectionsByCourse() {
        Map<CourseId, List<Section>> byCourse = new HashMap<>();
        for (SectionSeats s : sections) {
            byCourse.computeIfAbsent(s.section().course(), k -> new ArrayList<>()).add(s.section());
        }
        byCourse.values().forEach(list -> list.sort(Comparator.comparing(Section::id)));
        return byCourse;
    }

    int totalSeats() {
        return sections.stream().mapToInt(SectionSeats::available).sum();
    }
}
