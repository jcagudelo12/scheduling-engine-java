package sched.simulation.sensitivity;

import java.util.function.UnaryOperator;
import sched.simulation.experiment.ExperimentConfig;

/**
 * Una variación de la configuración base para el análisis de sensibilidad.
 *
 * @param id identificador corto
 * @param group grupo (para ordenar el reporte)
 * @param description qué se cambia
 * @param change transforma la configuración base
 */
public record Variation(String id, String group, String description, UnaryOperator<ExperimentConfig> change) {
}
