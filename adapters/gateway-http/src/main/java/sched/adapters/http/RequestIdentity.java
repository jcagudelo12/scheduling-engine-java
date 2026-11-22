package sched.adapters.http;

import java.util.HashSet;
import java.util.List;
import sched.adapters.http.CombinationsController.InvalidRequestException;

/**
 * Decide de dónde salen el estudiante y sus cursos habilitados. Con un {@link VerifiedStudent}
 * (modo {@code jwt}) salen del token firmado; si el cuerpo también los trae y no coinciden, la
 * solicitud es {@code 400}: es un cliente defectuoso o un intento de alterar el contexto. Sin
 * token verificado (modo {@code none}, solo desarrollo) salen del cuerpo, ya validados.
 */
final class RequestIdentity {

    private RequestIdentity() {
    }

    static String studentId(String fromBody, VerifiedStudent verified, RequestLimits limits) {
        if (verified == null) {
            return RequestValidation.id("student_id", fromBody, limits);
        }
        if (fromBody != null && !fromBody.equals(verified.studentId())) {
            throw new InvalidRequestException("student_id no coincide con el del token");
        }
        return verified.studentId();
    }

    /**
     * Los cursos habilitados, de al menos uno: sin asignaturas no hay nada que combinar, y en modo
     * {@code jwt} un token con {@code courses} vacío también es {@code 400} (el token es válido, la
     * solicitud no tiene sentido), no {@code 401}.
     */
    static List<String> eligibleCourses(List<String> fromBody, VerifiedStudent verified, RequestLimits limits) {
        List<String> courses;
        if (verified == null) {
            courses = RequestValidation.idList("eligible_courses", fromBody, limits.maxCourses(), limits);
        } else {
            if (fromBody != null && !new HashSet<>(fromBody).equals(new HashSet<>(verified.eligibleCourses()))) {
                throw new InvalidRequestException("eligible_courses no coincide con los cursos del token");
            }
            courses = verified.eligibleCourses();
        }
        if (courses.isEmpty()) {
            throw new InvalidRequestException("se requiere al menos una asignatura");
        }
        return courses;
    }
}
