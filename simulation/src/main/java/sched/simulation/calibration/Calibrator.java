package sched.simulation.calibration;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import sched.simulation.experiment.Calibration;
import sched.simulation.experiment.ExperimentConfig;

/**
 * Orquesta la calibración: búsqueda, ajuste dentro y fuera de muestra, y sensibilidad local de la
 * distancia a cada parámetro.
 */
public final class Calibrator {

    /** Cuántas semillas distintas de la de calibración se usan para validar. */
    public static final int VALIDATION_SEEDS = 5;
    /** Paso de la sensibilidad local, en fracción del rango de cada parámetro. */
    public static final double LOCAL_STEP = 0.10;
    /** Un parámetro cuyo cambio de LOCAL_STEP del rango mueve la distancia total menos que esto no se identifica. */
    public static final double IDENTIFIABILITY_THRESHOLD = 0.005;
    /** Tamaño de la consulta, para el piso de ruido de muestreo. */
    static final int SURVEY_SIZE = 135;

    /** Sensibilidad local de la distancia total a un parámetro, en el óptimo. */
    public record Sensitivity(ParamSpec spec, double value, double deltaDown, double deltaUp, boolean atBound,
                              boolean identifiable) {
        public double maxDelta() {
            return Math.max(Math.abs(deltaDown), Math.abs(deltaUp));
        }
    }

    /** Resultado completo. */
    public record Outcome(
            ParameterSpace space, ExperimentConfig base, ExperimentConfig calibrated, Search.Result search,
            Fit initial, Fit inSample, List<Long> validationSeeds, List<Fit> validation,
            List<Sensitivity> sensitivities, double[] floors, double[] values, double[] initialValues) {

        /** La primera semilla de validación: "la" evaluación fuera de muestra. */
        public Fit outOfSample() {
            return validation.getFirst();
        }

        public double validationMean() {
            return validation.stream().mapToDouble(Fit::total).average().orElse(Double.NaN);
        }
    }

    private Calibrator() {
    }

    public static Outcome calibrate(ExperimentConfig base, Calibration calibration, ParameterSpace space, int budget,
                                    ExecutorService pool) {
        Objective objective = Objective.of(calibration);
        Search search = new Search(space, base, objective, pool);
        Search.Result found = search.run(budget, base.seed() * 31 + 17);

        double[] values = space.values(found.best().unit());
        double[] initialValues = space.values(found.start().unit());
        ExperimentConfig calibrated = space.apply(base, values);
        Fit initial = objective.evaluate(base);
        Fit inSample = objective.evaluate(calibrated);

        List<Long> seeds = new ArrayList<>();
        List<Future<Fit>> futures = new ArrayList<>();
        for (int k = 1; k <= VALIDATION_SEEDS; k++) {
            long seed = base.seed() + 7919L * k;
            seeds.add(seed);
            ExperimentConfig other = calibrated.withSeed(seed);
            futures.add(pool.submit(() -> objective.evaluate(other)));
        }
        List<Fit> validation = new ArrayList<>();
        for (Future<Fit> f : futures) {
            validation.add(join(f));
        }

        List<Sensitivity> sensitivities = localSensitivity(space, base, objective, found.best().unit(),
                inSample.total(), pool);
        double[] floors = new double[3];
        List<Calibration.Category> a9 = calibration.redoneSelection();
        List<Calibration.Category> a11 = calibration.platformTime();
        List<Calibration.Category> a13 = calibration.lostSeat();
        floors[0] = Distance.samplingFloor(percents(a9), SURVEY_SIZE, 2000, 1);
        floors[1] = Distance.samplingFloor(percents(a11), SURVEY_SIZE, 2000, 2);
        floors[2] = Distance.samplingFloor(percents(a13), SURVEY_SIZE, 2000, 3);
        return new Outcome(space, base, calibrated, found, initial, inSample, seeds, validation, sensitivities,
                floors, values, initialValues);
    }

    private static double[] percents(List<Calibration.Category> categories) {
        return categories.stream().mapToDouble(Calibration.Category::percent).toArray();
    }

    static List<Sensitivity> localSensitivity(ParameterSpace space, ExperimentConfig base, Objective objective,
                                              double[] best, double bestValue, ExecutorService pool) {
        int n = space.size();
        List<Future<double[]>> futures = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            final int index = i;
            futures.add(pool.submit(() -> {
                double[] result = new double[] {Double.NaN, Double.NaN};
                for (int side = 0; side < 2; side++) {
                    double u = best[index] + (side == 0 ? -LOCAL_STEP : LOCAL_STEP);
                    if (u < 0 || u > 1) {
                        continue;
                    }
                    double[] x = best.clone();
                    x[index] = u;
                    result[side] = objective.evaluate(space.applyUnit(base, x)).total() - bestValue;
                }
                return result;
            }));
        }
        List<Sensitivity> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            double[] d = join(futures.get(i));
            double down = Double.isNaN(d[0]) ? 0 : d[0];
            double up = Double.isNaN(d[1]) ? 0 : d[1];
            double value = space.specs().get(i).denormalize(best[i]);
            ParamSpec spec = space.specs().get(i);
            boolean atBound = value - spec.lo() < 0.02 * (spec.hi() - spec.lo())
                    || spec.hi() - value < 0.02 * (spec.hi() - spec.lo());
            boolean identifiable = Math.max(Math.abs(down), Math.abs(up)) >= IDENTIFIABILITY_THRESHOLD;
            out.add(new Sensitivity(space.specs().get(i), value, down, up, atBound, identifiable));
        }
        return out;
    }

    private static <T> T join(Future<T> future) {
        try {
            return future.get();
        } catch (java.util.concurrent.ExecutionException e) {
            Throwable cause = e.getCause();
            throw cause instanceof RuntimeException re ? re : new IllegalStateException(cause);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
