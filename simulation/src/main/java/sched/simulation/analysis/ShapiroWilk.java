package sched.simulation.analysis;

import java.util.Arrays;
import java.util.SplittableRandom;
import org.apache.commons.statistics.distribution.NormalDistribution;

/**
 * Prueba de normalidad de Shapiro-Wilk con la aproximación de Royston (1995), algoritmo AS R94
 * (la misma que usa {@code shapiro.test} de R). Válida para 3 &le; n &le; 5000.
 *
 * <ul>
 *   <li>Muestra constante (rango &lt; 1e-19): W y p no están definidos; el resultado trae
 *       {@code constant = true} y {@link Result#normal(double)} es {@code false}, es decir se
 *       trata como no normal (una varianza nula impide usar la prueba t).</li>
 *   <li>n &lt; 3 o n &gt; 5000: {@link #test(double[])} lanza excepción. {@link #testSubsampling}
 *       toma, para n &gt; 5000, una submuestra aleatoria de 5000 con semilla fija.</li>
 * </ul>
 */
public final class ShapiroWilk {

    public static final int MIN_N = 3;
    public static final int MAX_N = 5000;
    private static final double SMALL = 1e-19;

    private static final double[] G = {-2.273, 0.459};
    private static final double[] C1 = {0, 0.221157, -0.147981, -2.07119, 4.434685, -2.706056};
    private static final double[] C2 = {0, 0.042981, -0.293762, -1.752461, 5.682633, -3.582633};
    private static final double[] C3 = {0.544, -0.39978, 0.025054, -6.714e-4};
    private static final double[] C4 = {1.3822, -0.77857, 0.062767, -0.0020322};
    private static final double[] C5 = {-1.5861, -0.31082, -0.083751, 0.0038915};
    private static final double[] C6 = {-0.4803, -0.082676, 0.0030302};

    /** {@code n}: tamaño original; {@code nUsed}: tamaño con el que se calculó (menor si hubo submuestreo). */
    public record Result(int n, int nUsed, double w, double pValue, boolean constant, boolean subsampled) {
        /** {@code true} si no se rechaza la normalidad al nivel alpha; nunca para muestras constantes. */
        public boolean normal(double alpha) {
            return !constant && pValue > alpha;
        }
    }

    private ShapiroWilk() {
    }

    /** @throws IllegalArgumentException si n &lt; 3 o n &gt; 5000 */
    public static Result test(double[] x) {
        int n = x.length;
        if (n < MIN_N || n > MAX_N) {
            throw new IllegalArgumentException("Shapiro-Wilk exige 3 <= n <= 5000, recibido n=" + n);
        }
        return compute(x, n, false);
    }

    /** Como {@link #test}, pero con n &gt; 5000 usa una submuestra aleatoria de 5000 (semilla fija). */
    public static Result testSubsampling(double[] x, long seed) {
        if (x.length <= MAX_N) {
            return test(x);
        }
        double[] copy = x.clone();
        SplittableRandom random = new SplittableRandom(seed);
        for (int i = 0; i < MAX_N; i++) { // Fisher-Yates parcial
            int j = i + random.nextInt(copy.length - i);
            double tmp = copy[i];
            copy[i] = copy[j];
            copy[j] = tmp;
        }
        return compute(Arrays.copyOf(copy, MAX_N), x.length, true);
    }

    private static Result compute(double[] data, int originalN, boolean subsampled) {
        double[] x = data.clone();
        Arrays.sort(x);
        int n = x.length;
        double range = x[n - 1] - x[0];
        if (range < SMALL) {
            return new Result(originalN, n, Double.NaN, Double.NaN, true, subsampled);
        }
        int nn2 = n / 2;
        double[] a = new double[nn2 + 1]; // 1-based, positivos: a[1] es el mayor
        double an = n;
        if (n == 3) {
            a[1] = Math.sqrt(0.5);
        } else {
            NormalDistribution std = NormalDistribution.of(0, 1);
            double an25 = an + 0.25;
            double summ2 = 0;
            for (int i = 1; i <= nn2; i++) {
                a[i] = std.inverseCumulativeProbability((i - 0.375) / an25);
                summ2 += a[i] * a[i];
            }
            summ2 *= 2;
            double ssumm2 = Math.sqrt(summ2);
            double rsn = 1 / Math.sqrt(an);
            double a1 = poly(C1, rsn) - a[1] / ssumm2;
            int i1;
            double fac;
            if (n > 5) {
                i1 = 3;
                double a2 = -a[2] / ssumm2 + poly(C2, rsn);
                fac = Math.sqrt((summ2 - 2 * a[1] * a[1] - 2 * a[2] * a[2])
                        / (1 - 2 * a1 * a1 - 2 * a2 * a2));
                a[2] = a2;
            } else {
                i1 = 2;
                fac = Math.sqrt((summ2 - 2 * a[1] * a[1]) / (1 - 2 * a1 * a1));
            }
            a[1] = a1;
            for (int i = i1; i <= nn2; i++) {
                a[i] /= -fac;
            }
        }
        // Coeficientes antisimétricos: el i-ésimo menor lleva -a[i+1], el i-ésimo mayor +a[i+1]; el central 0.
        double[] coef = new double[n];
        for (int i = 0; i < nn2; i++) {
            coef[i] = -a[i + 1];
            coef[n - 1 - i] = a[i + 1];
        }
        // W = correlación al cuadrado entre los datos y los coeficientes; se calcula 1 - W (w1) sin
        // cancelación, como en el original, escalando x por el rango.
        double sx = 0;
        double sa = 0;
        for (int i = 0; i < n; i++) {
            sx += x[i] / range;
            sa += coef[i];
        }
        sx /= n;
        sa /= n;
        double ssa = 0;
        double ssx = 0;
        double sax = 0;
        for (int i = 0; i < n; i++) {
            double asa = coef[i] - sa;
            double xsx = x[i] / range - sx;
            ssa += asa * asa;
            ssx += xsx * xsx;
            sax += asa * xsx;
        }
        double ssassx = Math.sqrt(ssa * ssx);
        double w1 = (ssassx - sax) * (ssassx + sax) / (ssa * ssx);
        double w = 1 - w1;
        if (n == 3) { // p exacto
            double p = 1.90985931710274 * (Math.asin(Math.sqrt(w)) - 1.04719755119660);
            return new Result(originalN, n, w, Math.max(p, 0), false, subsampled);
        }
        w1 = Math.max(w1, Double.MIN_NORMAL);
        double y = Math.log(w1);
        double xx = Math.log(an);
        double m;
        double s;
        if (n <= 11) {
            double gamma = poly(G, an);
            if (y >= gamma) {
                return new Result(originalN, n, w, 1e-99, false, subsampled);
            }
            y = -Math.log(gamma - y);
            m = poly(C3, an);
            s = Math.exp(poly(C4, an));
        } else {
            m = poly(C5, xx);
            s = Math.exp(poly(C6, xx));
        }
        double p = NormalDistribution.of(m, s).survivalProbability(y);
        return new Result(originalN, n, w, p, false, subsampled);
    }

    /** Polinomio c0 + c1 x + c2 x^2 + ... */
    private static double poly(double[] c, double x) {
        double r = 0;
        for (int i = c.length - 1; i >= 0; i--) {
            r = r * x + c[i];
        }
        return r;
    }
}
