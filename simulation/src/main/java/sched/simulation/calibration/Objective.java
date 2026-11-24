package sched.simulation.calibration;

import java.util.List;
import sched.simulation.experiment.Calibration;
import sched.simulation.experiment.CalibrationReport;
import sched.simulation.experiment.Experiment;
import sched.simulation.experiment.ExperimentConfig;
import sched.simulation.experiment.ResultRow;
import sched.simulation.experiment.StudentResult;

/**
 * Función objetivo: distancia ponderada entre las distribuciones simuladas del flujo A y las de la
 * consulta (A9, A11 y A13). Es una función pura de (configuración, semilla): se puede evaluar desde
 * varios hilos a la vez. Con la misma semilla en todas las evaluaciones (números aleatorios
 * comunes) las diferencias entre dos puntos se deben a los parámetros y no al azar.
 *
 * @param weights pesos de A9, A11 y A13 (se normalizan a suma 1)
 */
public record Objective(Calibration calibration, double[] weights, int replicas) {

    public static final double[] EQUAL_WEIGHTS = {1, 1, 1};
    /**
     * Semillas por evaluación: la distancia es el promedio sobre {@code replicas} semillas
     * (la semilla dada y {@code replicas - 1} derivadas, las mismas en todas las evaluaciones). Con
     * una sola semilla el ajuste se sobreajusta a la oferta y a las llegadas particulares de esa
     * semilla (se vio fuera de muestra).
     */
    public static final int DEFAULT_REPLICAS = 4;
    static final long SEED_STEP = 104_729L;

    public Objective {
        if (weights.length != 3) {
            throw new IllegalArgumentException("se necesitan los pesos de A9, A11 y A13");
        }
        if (replicas < 1) {
            throw new IllegalArgumentException("replicas debe ser al menos 1");
        }
    }

    public static Objective of(Calibration calibration) {
        return new Objective(calibration, EQUAL_WEIGHTS, DEFAULT_REPLICAS);
    }

    /** Promedio del ajuste sobre las {@code replicas} semillas derivadas de la de {@code config}. */
    public Fit evaluate(ExperimentConfig config) {
        Fit first = evaluateOne(config);
        if (replicas == 1) {
            return first;
        }
        double[] distances = first.distances().clone();
        double total = first.total();
        double clashes = first.meanClashes();
        double seats = first.meanSeatRejections();
        double share = first.enrolledShare();
        List<CalibrationReport.Comparison> tables = new java.util.ArrayList<>();
        double[][] sim = new double[3][];
        for (int t = 0; t < 3; t++) {
            sim[t] = first.tables().get(t).simulated().clone();
        }
        for (int k = 1; k < replicas; k++) {
            Fit f = evaluateOne(config.withSeed(config.seed() + SEED_STEP * k));
            for (int t = 0; t < 3; t++) {
                distances[t] += f.distances()[t];
                for (int i = 0; i < sim[t].length; i++) {
                    sim[t][i] += f.tables().get(t).simulated()[i];
                }
            }
            total += f.total();
            clashes += f.meanClashes();
            seats += f.meanSeatRejections();
            share += f.enrolledShare();
        }
        for (int t = 0; t < 3; t++) {
            distances[t] /= replicas;
            for (int i = 0; i < sim[t].length; i++) {
                sim[t][i] /= replicas;
            }
            CalibrationReport.Comparison c = first.tables().get(t);
            tables.add(new CalibrationReport.Comparison(c.name(), c.labels(), c.survey(), sim[t]));
        }
        return new Fit(distances, total / replicas, tables, clashes / replicas, seats / replicas, share / replicas);
    }

    private Fit evaluateOne(ExperimentConfig config) {
        List<StudentResult> reactive = Experiment.runReactive(config, calibration);
        List<ResultRow> rows = reactive.stream().map(StudentResult::toRow).toList();
        List<CalibrationReport.Comparison> all = CalibrationReport.compare(rows, calibration);
        List<CalibrationReport.Comparison> tables = List.of(all.get(0), all.get(1), all.get(2));
        double[] distances = new double[3];
        double sumWeights = 0;
        double total = 0;
        for (int i = 0; i < 3; i++) {
            distances[i] = Distance.hellinger(tables.get(i).survey(), tables.get(i).simulated());
            sumWeights += weights[i];
            total += weights[i] * distances[i];
        }
        double wanted = reactive.stream().mapToInt(r -> r.profile().courses().size()).sum();
        double enrolled = reactive.stream().mapToInt(StudentResult::coursesEnrolled).sum();
        return new Fit(distances, total / sumWeights, tables,
                reactive.stream().mapToInt(StudentResult::rTraslape).average().orElse(0),
                reactive.stream().mapToInt(StudentResult::rCupo).average().orElse(0),
                wanted == 0 ? 0 : enrolled / wanted);
    }
}
