package sched.simulation.experiment;

/** Las columnas de una fila de resultados que usa el informe de calibración. */
public record ResultRow(
        String profileId, String condition, double tTotalSeconds, int rTraslape, int rCupo,
        int rCupoInClash, int nearLoss) {
}
