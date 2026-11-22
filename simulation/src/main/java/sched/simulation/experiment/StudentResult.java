package sched.simulation.experiment;

/**
 * Resultado de un perfil en una condición.
 *
 * @param tTotalSeconds segundos desde que inicia la selección hasta que confirma
 * @param rTraslape envíos rechazados por cruce, contados por la plataforma
 * @param rCupo envíos rechazados por falta de cupo
 * @param coursesEnrolled asignaturas inscritas al confirmar
 * @param coursesDeferred asignaturas que quería y quedaron sin inscribir
 * @param engineCalls consultas al motor (0 en A)
 * @param engineNanos cómputo real del motor, sumado al tiempo simulado
 * @param withdrawals retiros de grupos ya inscritos (retrocesos en A, cambios de alternativa en B)
 * @param rCupoInClash rechazos por cupo en una asignatura que ya tenía un rechazo por cruce pendiente
 * @param nearLoss cupos tomados, con un cruce pendiente en la asignatura, con muy pocos cupos
 *     restantes (ver {@code nearLossSeats})
 */
public record StudentResult(
        Profile profile,
        Condition condition,
        double tTotalSeconds,
        int rTraslape,
        int rCupo,
        int coursesEnrolled,
        int coursesDeferred,
        int engineCalls,
        long engineNanos,
        int withdrawals,
        int rCupoInClash,
        int nearLoss) {

    public int rTotal() {
        return rTraslape + rCupo;
    }

    public ResultRow toRow() {
        return new ResultRow(profile.id(), condition.code(), tTotalSeconds, rTraslape, rCupo, rCupoInClash, nearLoss);
    }
}
