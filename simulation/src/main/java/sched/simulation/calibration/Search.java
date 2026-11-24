package sched.simulation.calibration;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.SplittableRandom;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import sched.simulation.experiment.ExperimentConfig;

/**
 * Búsqueda de parámetros: hipercubo latino (exploración global) y refinamiento local con
 * Nelder-Mead desde los mejores puntos, todo en el cubo unitario normalizado y con los mismos
 * números aleatorios en cada evaluación (la semilla de {@code base}). Las evaluaciones son
 * independientes y corren en un pool de hilos; el resultado no depende del orden en que terminen
 * (cada punto tiene su valor, y cada Nelder-Mead es secuencial y determinista).
 */
public final class Search {

    /** Punto del cubo unitario con su distancia. */
    public record Point(double[] unit, double value) {
    }

    public record Result(Point best, Point start, int evaluations, int latinHypercubePoints, int refinementStarts) {
    }

    private static final int REFINEMENT_STARTS = 12;
    private static final double LHS_SHARE = 0.5;

    private final ParameterSpace space;
    private final ExperimentConfig base;
    private final Objective objective;
    private final ExecutorService pool;
    private final AtomicInteger evaluations = new AtomicInteger();

    public Search(ParameterSpace space, ExperimentConfig base, Objective objective, ExecutorService pool) {
        this.space = space;
        this.base = base;
        this.objective = objective;
        this.pool = pool;
    }

    public static ExecutorService newPool(int threads) {
        return Executors.newFixedThreadPool(Math.max(1, threads), r -> {
            Thread t = new Thread(r, "calibration");
            t.setDaemon(true);
            return t;
        });
    }

    /** Distancia total en un punto del cubo unitario. */
    public double value(double[] unit) {
        evaluations.incrementAndGet();
        return objective.evaluate(space.applyUnit(base, unit)).total();
    }

    public Result run(int budget, long searchSeed) {
        double[] startUnit = space.unitOf(base);
        Point start = new Point(startUnit, value(startUnit));

        int lhsCount = Math.max(2, (int) (budget * LHS_SHARE));
        List<double[]> candidates = latinHypercube(lhsCount, space.size(), new SplittableRandom(searchSeed));
        double[] values = valuesOf(candidates);
        List<Point> ranked = new ArrayList<>();
        ranked.add(start);
        for (int i = 0; i < candidates.size(); i++) {
            ranked.add(new Point(candidates.get(i), values[i]));
        }
        ranked.sort(Comparator.comparingDouble(Point::value));

        int starts = Math.max(1, Math.min(REFINEMENT_STARTS, ranked.size()));
        int perStart = Math.max(space.size() + 2, (budget - lhsCount) / starts);
        List<Future<Point>> refinements = new ArrayList<>();
        for (int i = 0; i < starts; i++) {
            Point from = ranked.get(i);
            refinements.add(pool.submit(() -> nelderMead(from, perStart)));
        }
        Point best = ranked.getFirst();
        for (Future<Point> f : refinements) {
            Point p = get(f);
            if (p.value() < best.value()) {
                best = p;
            }
        }
        return new Result(best, start, evaluations.get(), lhsCount, starts);
    }

    private double[] valuesOf(List<double[]> points) {
        List<Future<Double>> futures = new ArrayList<>();
        for (double[] p : points) {
            futures.add(pool.submit(() -> value(p)));
        }
        double[] values = new double[points.size()];
        for (int i = 0; i < values.length; i++) {
            values[i] = get(futures.get(i));
        }
        return values;
    }

    private static <T> T get(Future<T> future) {
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

    /** Hipercubo latino: cada dimensión dividida en n estratos, uno por punto, con jitter. */
    static List<double[]> latinHypercube(int n, int dims, SplittableRandom random) {
        double[][] points = new double[n][dims];
        for (int d = 0; d < dims; d++) {
            int[] perm = new int[n];
            for (int i = 0; i < n; i++) {
                perm[i] = i;
            }
            for (int i = n - 1; i > 0; i--) {
                int j = random.nextInt(i + 1);
                int tmp = perm[i];
                perm[i] = perm[j];
                perm[j] = tmp;
            }
            for (int i = 0; i < n; i++) {
                points[i][d] = (perm[i] + random.nextDouble()) / n;
            }
        }
        return Arrays.asList(points);
    }

    /**
     * Nelder-Mead en el cubo unitario (los puntos se recortan a [0, 1]); termina al agotar el
     * presupuesto de evaluaciones o cuando el símplex y las diferencias de valor son diminutas.
     */
    Point nelderMead(Point from, int maxEvaluations) {
        int n = from.unit().length;
        List<Point> simplex = new ArrayList<>();
        simplex.add(from);
        int used = 0;
        for (int i = 0; i < n; i++) {
            double[] x = from.unit().clone();
            x[i] = x[i] + (x[i] > 0.9 ? -0.1 : 0.1);
            simplex.add(evaluated(x));
            used++;
        }
        while (used < maxEvaluations) {
            simplex.sort(Comparator.comparingDouble(Point::value));
            Point best = simplex.getFirst();
            Point worst = simplex.getLast();
            if (worst.value() - best.value() < 1e-7 && size(simplex) < 1e-4) {
                break;
            }
            double[] centroid = new double[n];
            for (int i = 0; i < n; i++) {
                for (int k = 0; k < n; k++) {
                    centroid[k] += simplex.get(i).unit()[k] / n;
                }
            }
            Point reflected = evaluated(along(centroid, worst.unit(), -1));
            used++;
            if (reflected.value() < best.value()) {
                Point expanded = evaluated(along(centroid, worst.unit(), -2));
                used++;
                simplex.set(n, expanded.value() < reflected.value() ? expanded : reflected);
            } else if (reflected.value() < simplex.get(n - 1).value()) {
                simplex.set(n, reflected);
            } else {
                boolean outside = reflected.value() < worst.value();
                Point contracted = evaluated(along(centroid, worst.unit(), outside ? -0.5 : 0.5));
                used++;
                if (contracted.value() < Math.min(reflected.value(), worst.value())) {
                    simplex.set(n, contracted);
                } else {
                    for (int i = 1; i <= n; i++) {
                        double[] x = new double[n];
                        for (int k = 0; k < n; k++) {
                            x[k] = best.unit()[k] + 0.5 * (simplex.get(i).unit()[k] - best.unit()[k]);
                        }
                        simplex.set(i, evaluated(x));
                        used++;
                    }
                }
            }
        }
        simplex.sort(Comparator.comparingDouble(Point::value));
        return simplex.getFirst();
    }

    /** centroide + factor * (peor - centroide), recortado al cubo. factor -1 = reflexión. */
    private static double[] along(double[] centroid, double[] worst, double factor) {
        double[] x = new double[centroid.length];
        for (int k = 0; k < x.length; k++) {
            x[k] = Math.max(0, Math.min(1, centroid[k] + factor * (worst[k] - centroid[k])));
        }
        return x;
    }

    private Point evaluated(double[] unit) {
        double[] clamped = new double[unit.length];
        for (int i = 0; i < unit.length; i++) {
            clamped[i] = Math.max(0, Math.min(1, unit[i]));
        }
        return new Point(clamped, value(clamped));
    }

    private static double size(List<Point> simplex) {
        double max = 0;
        Point first = simplex.getFirst();
        for (Point p : simplex) {
            for (int k = 0; k < p.unit().length; k++) {
                max = Math.max(max, Math.abs(p.unit()[k] - first.unit()[k]));
            }
        }
        return max;
    }
}
