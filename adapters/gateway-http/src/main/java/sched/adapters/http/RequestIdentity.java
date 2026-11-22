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

    static List<String> eligibleCourses(List<String> fromBody, VerifiedStudent verified, RequestLimits limits) {
        if (verified == null) {
            return RequestValidation.idList("eligible_courses", fromBody, limits.maxCourses(), limits);
        }
        if (fromBody != null && !new HashSet<>(fromBody).equals(new HashSet<>(verified.eligibleCourses()))) {
            throw new InvalidRequestException("eligible_courses no coincide con los cursos del token");
        }
        return verified.eligibleCourses();
    }
}
