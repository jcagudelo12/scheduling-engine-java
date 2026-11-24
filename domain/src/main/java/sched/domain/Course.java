package sched.domain;

/**
 * Asignatura del periodo con sus créditos.
 *
 * <p>Los créditos dependen de la asignatura, no del grupo (todos los grupos de un curso valen lo mismo).
 */
public record Course(CourseId id, int credits) {

    public Course {
        if (credits < 0) {
            throw new IllegalArgumentException("créditos negativos en el curso " + id.value() + ": " + credits);
        }
    }
}
