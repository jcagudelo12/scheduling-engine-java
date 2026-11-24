package sched.simulation.analysis;

import java.util.Arrays;
import java.util.SplittableRandom;

/** Bootstrap percentil de la media: remuestreo con reemplazo y semilla fija (reproducible). */
public final class Bootstrap {

    public static final int DEFAULT_RESAMPLES = 10_000;

    private Bootstrap() {
    }

    /**
     * IC percentil {@code level} de la media: cuantiles (1 - level)/2 y 1 - (1 - level)/2 de las medias
     * de {@code resamples} remuestras de tamaño n (cuantil con interpolación lineal).
     */
    public static double[] meanConfidenceInterval(double[] x, double level, int resamples, long seed) {
        if (x.length == 0) {
            throw new IllegalArgumentException("muestra vacía");
        }
        SplittableRandom random = new SplittableRandom(seed);
        double[] means = new double[resamples];
        int n = x.length;
        for (int b = 0; b < resamples; b++) {
            double sum = 0;
            for (int i = 0; i < n; i++) {
                sum += x[random.nextInt(n)];
            }
            means[b] = sum / n;
        }
        Arrays.sort(means);
        double tail = (1 - level) / 2;
        return new double[] {Descriptive.quantileSorted(means, tail), Descriptive.quantileSorted(means, 1 - tail)};
    }
}
