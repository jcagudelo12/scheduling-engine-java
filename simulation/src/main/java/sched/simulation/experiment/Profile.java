package sched.simulation.experiment;

import java.util.List;
import sched.domain.CourseId;

/**
 * Estudiante sintético. Es el mismo en las dos condiciones.
 *
 * @param index posición (empieza en 0); identifica al estudiante en la plataforma
 * @param program programa académico
 * @param semester semestre (1 a 10)
 * @param nIntended asignaturas que quería inscribir (A6)
 * @param window ventana de matrícula (empieza en 1)
 * @param preference jornada preferida
 * @param courses asignaturas a inscribir, por prioridad: las de su semestre primero y, si el
 *     tope de créditos no deja cursar {@code nIntended}, menos
 * @param arrivalSeconds instante en que empieza su selección
 */
public record Profile(
        int index,
        String id,
        String program,
        int semester,
        int nIntended,
        int window,
        Shift preference,
        List<CourseId> courses,
        double arrivalSeconds) {

    public Profile {
        courses = List.copyOf(courses);
    }

    Profile withArrival(double seconds) {
        return new Profile(index, id, program, semester, nIntended, window, preference, courses, seconds);
    }
}
