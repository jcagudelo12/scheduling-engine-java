package sched.simulation.analysis;

import java.util.Arrays;
import org.apache.commons.statistics.distribution.TDistribution;

/** Estadísticos descriptivos y el intervalo de confianza t de la media. */
public final class Descriptive {

    /** Resumen de una muestra. {@code sd} es la desviación estándar muestral (n - 1). */
    public record Summary(int n, double mean, double median, double sd, double q1, double q3, double iqr,
                          double min, double max) {
    }

    private Descriptive() {
    }

    public static Summary summary(double[] x) {
        if (x.length == 0) {
            throw new IllegalArgumentException("muestra vacía");
        }
        double[] sorted = x.clone();
        Arrays.sort(sorted);
        double q1 = quantileSorted(sorted, 0.25);
        double q3 = quantileSorted(sorted, 0.75);
        return new Summary(x.length, mean(x), quantileSorted(sorted, 0.5), sd(x), q1, q3, q3 - q1,
                sorted[0], sorted[sorted.length - 1]);
    }

    public static double mean(double[] x) {
        double sum = 0;
        for (double v : x) {
            sum += v;
        }
        return sum / x.length;
    }

    /** Desviación estándar muestral; NaN si n &lt; 2. */
    public static double sd(double[] x) {
        if (x.length < 2) {
            return Double.NaN;
        }
        double m = mean(x);
        double ss = 0;
        for (double v : x) {
            ss += (v - m) * (v - m);
        }
        return Math.sqrt(ss / (x.length - 1));
    }

    public static double median(double[] x) {
        return quantile(x, 0.5);
    }

    /** Cuantil con interpolación lineal (tipo 7, el predeterminado de R y NumPy). */
    public static double quantile(double[] x, double p) {
        double[] sorted = x.clone();
        Arrays.sort(sorted);
        return quantileSorted(sorted, p);
    }

    static double quantileSorted(double[] sorted, double p) {
        double h = (sorted.length - 1) * p;
        int lo = (int) Math.floor(h);
        int hi = (int) Math.ceil(h);
        return sorted[lo] + (h - lo) * (sorted[hi] - sorted[lo]);
    }

    /** IC t de la media con nivel {@code level} (p. ej. 0,95): {@code [inferior, superior]}. */
    public static double[] meanConfidenceInterval(double[] x, double level) {
        double m = mean(x);
        if (x.length < 2) {
            return new double[] {Double.NaN, Double.NaN};
        }
        double half = TDistribution.of(x.length - 1).inverseSurvivalProbability((1 - level) / 2)
                * sd(x) / Math.sqrt(x.length);
        return new double[] {m - half, m + half};
    }
}
