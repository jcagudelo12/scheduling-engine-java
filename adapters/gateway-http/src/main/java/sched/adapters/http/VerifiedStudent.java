package sched.adapters.http;

import java.util.List;

/**
 * Contexto del estudiante ya verificado: sale de los claims de un token con firma válida
 * ({@link JwtAuthFilter}) y los controladores lo reciben como atributo de la solicitud. Cuando
 * existe, manda sobre lo que diga el cuerpo (ADR 0004).
 *
 * @param studentId claim {@code sub}
 * @param eligibleCourses claim {@code courses}: asignaturas habilitadas
 */
public record VerifiedStudent(String studentId, List<String> eligibleCourses) {

    /** Nombre del atributo de la solicitud donde el filtro deja el contexto verificado. */
    public static final String ATTRIBUTE = "sched.adapters.http.VerifiedStudent";

    public VerifiedStudent {
        eligibleCourses = List.copyOf(eligibleCourses);
    }
}
