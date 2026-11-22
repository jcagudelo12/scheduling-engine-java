package sched.simulation.sensitivity;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import sched.simulation.analysis.Analysis;
import sched.simulation.analysis.PairedResults;
import sched.simulation.experiment.Experiment;
import sched.simulation.experiment.ExperimentConfig;

/** Corre el experimento pareado y el análisis para cada variación, réplicas y punto de quiebre. */
public final class SensitivityRunner {

    public static final double MU0 = 0.20;
    public static final double ALPHA = 0.05;
    /** Remuestreos del bootstrap en la sensibilidad (menos que los 10.000 del análisis principal: son muchas corridas). */
    public static final int BOOTSTRAP_RESAMPLES = 2000;
    /** Cota superior del factor de B en la búsqueda del punto de quiebre. */
    public static final double MAX_FACTOR = 4096;

    /** Resultado de una corrida pareada analizada. */
    public record Row(String id, String group, String description, double meanReduction, double bootstrapLow,
                      double bootstrapHigh, double pValue, String test, boolean reject, double meanA, double meanB,
                      double enrolledGap, boolean clashOk, double sdReduction, int profiles) {
    }

    /** Resumen de réplicas con semillas distintas. */
    public record Replicas(int count, List<Long> seeds, List<Double> meanReductions, double mean, double sd,
                           double min, double max, int rejections, double meanP) {
    }

    /** Punto de quiebre: factor sobre los tiempos propios de B con el que la reducción media cae a mu0. */
    public record Breakpoint(boolean found, double factor, double reductionAtBase, double reductionAtFactor,
                             double reviewCombinationSeconds, double reviewAlternativeSeconds, boolean beyondMax,
                             int runs) {
    }

    /**
     * Punto de quiebre de la navegación: fracción del tiempo de navegar la asignatura que B puede pagar
     * por cada grupo que inscribe sin que la reducción media caiga a mu0 (0 = nada, 1 = lo mismo que A).
     */
    public record BrowseBreakpoint(boolean needed, double factor, double reductionAtFull, double reductionAtZero,
                                   double browseSeconds, int runs) {
    }

    private SensitivityRunner() {
    }

    /** Corre y analiza una configuración. */
    public static Row run(String id, String group, String description, ExperimentConfig config) {
        Experiment.Result result = Experiment.run(config);
        List<PairedResults.Pair> pairs = PairedResults.of(result.reactive(), result.assisted());
        Analysis.Result a = Analysis.run(pairs, new Analysis.Params(ALPHA, MU0, config.seed(), BOOTSTRAP_RESAMPLES));
        return new Row(id, group, description, a.main().reduction().mean(), a.main().bootstrapCiLow(),
                a.main().bootstrapCiHigh(), a.main().test().pValue(), a.main().test().test(), a.main().reject(),
                a.descriptives().get("A").get("t_total_s").mean(), a.descriptives().get("B").get("t_total_s").mean(),
                a.validity().difference(), a.clash().ok(), a.main().reduction().sd(), pairs.size());
    }

    public static List<Row> runAll(ExperimentConfig base, List<Variation> variations, ExecutorService pool) {
        List<Future<Row>> futures = new ArrayList<>();
        for (Variation v : variations) {
            futures.add(pool.submit(() -> run(v.id(), v.group(), v.description(), v.change().apply(base))));
        }
        List<Row> rows = new ArrayList<>();
        for (Future<Row> f : futures) {
            rows.add(join(f));
        }
        return rows;
    }

    /** Réplicas: la misma configuración con {@code count} semillas distintas (la primera es la de la configuración). */
    public static Replicas replicate(ExperimentConfig base, int count, ExecutorService pool) {
        List<Long> seeds = new ArrayList<>();
        List<Future<Row>> futures = new ArrayList<>();
        for (int k = 0; k < count; k++) {
            long seed = base.seed() + 10_007L * k;
            seeds.add(seed);
            ExperimentConfig config = base.withSeed(seed);
            futures.add(pool.submit(() -> run("replica", "réplicas", "semilla " + seed, config)));
        }
        List<Double> means = new ArrayList<>();
        int rejections = 0;
        double p = 0;
        for (Future<Row> f : futures) {
            Row row = join(f);
            means.add(row.meanReduction());
            rejections += row.reject() ? 1 : 0;
            p += row.pValue();
        }
        double mean = means.stream().mapToDouble(Double::doubleValue).average().orElse(Double.NaN);
        double var = count < 2 ? Double.NaN
                : means.stream().mapToDouble(m -> (m - mean) * (m - mean)).sum() / (count - 1);
        return new Replicas(count, seeds, means, mean, Math.sqrt(var),
                means.stream().mapToDouble(Double::doubleValue).min().orElse(Double.NaN),
                means.stream().mapToDouble(Double::doubleValue).max().orElse(Double.NaN), rejections, p / count);
    }

    /** Reducción media de r (sin pruebas) con los tiempos propios de B multiplicados por {@code factor}. */
    static double meanReduction(ExperimentConfig base, double factor) {
        ExperimentConfig config = Variations.scale(base, factor, Variations.B_OWN_TIMES);
        Experiment.Result result = Experiment.run(config);
        double sum = 0;
        int n = 0;
        for (int i = 0; i < result.reactive().size(); i++) {
            double a = result.reactive().get(i).tTotalSeconds();
            if (a > 0) {
                sum += (a - result.assisted().get(i).tTotalSeconds()) / a;
                n++;
            }
        }
        return sum / n;
    }

    /**
     * Punto de quiebre: se duplica el factor hasta que la reducción media cae a {@code mu0} o menos y
     * se biseca (en escala logarítmica) hasta 1 % de precisión relativa.
     */
    public static Breakpoint breakpoint(ExperimentConfig base, double mu0) {
        int runs = 0;
        double atBase = meanReduction(base, 1);
        runs++;
        if (atBase <= mu0) {
            return new Breakpoint(true, 1, atBase, atBase, base.valueAt("times.reviewCombination.meanSeconds"),
                    base.valueAt("times.reviewAlternative.meanSeconds"), false, runs);
        }
        double lo = 1;
        double hi = 2;
        double atHi = meanReduction(base, hi);
        runs++;
        while (atHi > mu0 && hi < MAX_FACTOR) {
            lo = hi;
            hi *= 2;
            atHi = meanReduction(base, hi);
            runs++;
        }
        if (atHi > mu0) {
            return new Breakpoint(false, hi, atBase, atHi, base.valueAt("times.reviewCombination.meanSeconds") * hi,
                    base.valueAt("times.reviewAlternative.meanSeconds") * hi, true, runs);
        }
        double atMid = atHi;
        double mid = hi;
        while (hi / lo > 1.01 && runs < 60) {
            mid = Math.sqrt(lo * hi);
            atMid = meanReduction(base, mid);
            runs++;
            if (atMid > mu0) {
                lo = mid;
            } else {
                hi = mid;
            }
        }
        double factor = Math.sqrt(lo * hi);
        double atFactor = meanReduction(base, factor);
        runs++;
        return new Breakpoint(true, factor, atBase, atFactor, base.valueAt("times.reviewCombination.meanSeconds") * factor,
                base.valueAt("times.reviewAlternative.meanSeconds") * factor, false, runs);
    }

    /** Reducción media de r con B pagando {@code factor} del tiempo de navegar por grupo inscrito. */
    static double meanReductionWithBrowse(ExperimentConfig base, double factor) {
        return meanReduction(Variations.set(base, "behavior.assistedBrowseFactor", factor), 1);
    }

    /**
     * Busca (bisección en [0, 1]) la fracción del tiempo de navegar que B puede pagar con la reducción media
     * en mu0. Si con 1 (el mismo costo que A) ya se supera mu0, no hace falta ningún descuento.
     */
    public static BrowseBreakpoint browseBreakpoint(ExperimentConfig base, double mu0) {
        double browse = base.valueAt("times.browseCourse.meanSeconds");
        int runs = 1;
        double atFull = meanReductionWithBrowse(base, 1);
        if (atFull > mu0) {
            return new BrowseBreakpoint(false, 1, atFull, Double.NaN, browse, runs);
        }
        double atZero = meanReductionWithBrowse(base, 0);
        runs++;
        if (atZero <= mu0) {
            return new BrowseBreakpoint(true, 0, atFull, atZero, browse, runs);
        }
        double lo = 0;
        double hi = 1;
        while (hi - lo > 0.005 && runs < 40) {
            double mid = (lo + hi) / 2;
            runs++;
            if (meanReductionWithBrowse(base, mid) > mu0) {
                lo = mid;
            } else {
                hi = mid;
            }
        }
        return new BrowseBreakpoint(true, (lo + hi) / 2, atFull, atZero, browse, runs);
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
