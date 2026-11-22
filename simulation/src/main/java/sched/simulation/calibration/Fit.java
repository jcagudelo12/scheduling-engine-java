package sched.simulation.calibration;

import java.util.List;
import sched.simulation.experiment.CalibrationReport;

/**
 * Ajuste del flujo A a la consulta con una configuración y una semilla.
 *
 * @param distances distancia de Hellinger de A9, A11 y A13, en ese orden
 * @param total suma ponderada de las tres
 * @param tables comparación por categoría (A9, A11, A13)
 * @param meanClashes media de Rtraslape simulada
 * @param meanSeatRejections media de Rcupo simulada
 * @param enrolledShare asignaturas matriculadas sobre las pedidas, en A (plausibilidad)
 */
public record Fit(
        double[] distances, double total, List<CalibrationReport.Comparison> tables, double meanClashes,
        double meanSeatRejections, double enrolledShare) {

    public static final List<String> TABLE_NAMES = List.of("A9", "A11", "A13");
}
