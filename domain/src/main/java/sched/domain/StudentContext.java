package sched.domain;

import java.util.LinkedHashSet;
import java.util.List;

/**
 * Datos del estudiante que entrega la institución en cada solicitud (ADR 0004), más sus
 * preferencias de horario.
 *
 * <p>Las preferencias viajan aquí porque son parte de lo que se sabe del estudiante en la
 * consulta, igual que sus cursos: los casos de uso que las necesitan (combinaciones y, más
 * adelante, alternativas) las leen del mismo contexto y solo agregan lo propio (por ejemplo, la
 * selección actual).
 */
public record StudentContext(StudentId id, List<CourseId> eligibleCourses, Preferences preferences) {

    public StudentContext {
        // Sin repetidos, conservando el orden de primera aparición.
        eligibleCourses = List.copyOf(new LinkedHashSet<>(eligibleCourses));
        preferences = preferences == null ? Preferences.NONE : preferences;
    }

    /** Estudiante sin preferencias. */
    public StudentContext(StudentId id, List<CourseId> eligibleCourses) {
        this(id, eligibleCourses, Preferences.NONE);
    }
}
