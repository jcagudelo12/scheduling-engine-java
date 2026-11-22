package sched.domain;

import java.util.LinkedHashSet;
import java.util.List;

/** Datos del estudiante que entrega la institución en cada solicitud (ADR 0004). */
public record StudentContext(StudentId id, List<CourseId> eligibleCourses) {

    public StudentContext {
        // Sin repetidos, conservando el orden de primera aparición.
        eligibleCourses = List.copyOf(new LinkedHashSet<>(eligibleCourses));
    }
}
