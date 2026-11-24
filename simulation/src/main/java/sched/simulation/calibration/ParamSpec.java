package sched.simulation.calibration;

import java.util.List;

/**
 * Un parámetro a calibrar: una o varias rutas de la configuración que toman el mismo valor, sus
 * cotas y por qué son plausibles.
 *
 * @param name nombre corto para el reporte
 * @param paths rutas con puntos de {@code ExperimentConfig} que se fijan al mismo valor
 * @param lo cota inferior
 * @param hi cota superior
 * @param rationale por qué esas cotas son plausibles
 * @param integer si el parámetro es entero (se redondea)
 */
public record ParamSpec(
        String name, List<String> paths, double lo, double hi, String rationale, boolean integer) {

    public ParamSpec(String name, List<String> paths, double lo, double hi, String rationale) {
        this(name, paths, lo, hi, rationale, false);
    }

    public ParamSpec {
        if (!(hi > lo)) {
            throw new IllegalArgumentException("cotas inválidas para " + name);
        }
        paths = List.copyOf(paths);
    }

    public double clamp(double value) {
        return Math.max(lo, Math.min(hi, value));
    }

    /** Valor real a partir de la coordenada normalizada u en [0, 1]. */
    public double denormalize(double u) {
        return lo + Math.max(0, Math.min(1, u)) * (hi - lo);
    }

    public double normalize(double value) {
        return (clamp(value) - lo) / (hi - lo);
    }
}
