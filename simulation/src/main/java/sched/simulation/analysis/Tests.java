package sched.simulation.analysis;

import java.util.Arrays;
import org.apache.commons.statistics.inference.AlternativeHypothesis;
import org.apache.commons.statistics.inference.TTest;
import org.apache.commons.statistics.inference.WilcoxonSignedRankTest;

/**
 * Pruebas t y de Wilcoxon de una muestra (una prueba pareada es la de una muestra sobre las diferencias),
 * sobre Apache Commons Statistics 1.x.
 *
 * <p>Wilcoxon: se usa {@code WilcoxonSignedRankTest} con {@code PValueMethod.AUTO} y corrección de
 * continuidad: p exacto si n &le; 50 y no hay empates ni ceros; si no, aproximación normal de Cureton, que
 * corrige por empates (rangos medios) y por ceros (los ceros, es decir los valores iguales a mu, se ordenan
 * con los demás y se descuentan de la media y la varianza de W+). El estadístico es W+, la suma de rangos
 * de las diferencias positivas. El Z del tamaño del efecto es el de esa misma aproximación, sin la
 * corrección de continuidad.
 */
public final class Tests {

    public enum Alternative {
        TWO_SIDED(AlternativeHypothesis.TWO_SIDED),
        GREATER(AlternativeHypothesis.GREATER_THAN),
        LESS(AlternativeHypothesis.LESS_THAN);

        final AlternativeHypothesis commons;

        Alternative(AlternativeHypothesis commons) {
            this.commons = commons;
        }
    }

    /**
     * Resultado de una prueba. {@code df} es NaN en Wilcoxon; {@code effectSize} es la d de Cohen
     * (t) o r = Z/&radic;n (Wilcoxon); {@code z} es NaN en t.
     */
    public record Outcome(String test, double statistic, double df, double pValue, String effectName,
                          double effectSize, double z, String method, int n) {
        public boolean reject(double alpha) {
            return pValue < alpha;
        }
    }

    private Tests() {
    }

    /** t de una muestra de {@code x} frente a {@code mu}. d de Cohen = (media - mu) / sd. */
    public static Outcome tTest(double[] x, double mu, Alternative alternative) {
        TTest.Result r = TTest.withDefaults().with(alternative.commons).withMu(mu).test(x);
        double d = (Descriptive.mean(x) - mu) / Descriptive.sd(x);
        return new Outcome("t de Student", r.getStatistic(), r.getDegreesOfFreedom(), r.getPValue(),
                "d de Cohen", d, Double.NaN, "distribución t con n - 1 gl", x.length);
    }

    /** Wilcoxon de rangos con signo de {@code x - mu}. r = Z / raíz(n). */
    public static Outcome wilcoxon(double[] x, double mu, Alternative alternative) {
        int n = x.length;
        double[] shifted = new double[n];
        for (int i = 0; i < n; i++) {
            shifted[i] = x[i] - mu;
        }
        if (Arrays.stream(shifted).allMatch(v -> v == 0)) {
            return new Outcome("Wilcoxon de rangos con signo", 0, Double.NaN, 1.0, "r (Z/√n)", 0, 0,
                    "todas las diferencias son iguales a mu: sin evidencia", n);
        }
        WilcoxonSignedRankTest.Result r = WilcoxonSignedRankTest.withDefaults().with(alternative.commons)
                .test(shifted);
        double z = zScore(r.getStatistic(), shifted);
        String method = !r.hasTiedValues() && !r.hasZeroValues() && n <= 50
                ? "exacto"
                : "aproximación normal con corrección de continuidad"
                + (r.hasTiedValues() ? ", corrección por empates" : "")
                + (r.hasZeroValues() ? ", tratamiento de ceros" : "");
        return new Outcome("Wilcoxon de rangos con signo", r.getStatistic(), Double.NaN, r.getPValue(),
                "r (Z/√n)", z / Math.sqrt(n), z, method, n);
    }

    /** Z de la aproximación normal de W+ (Cureton: empates y ceros), sin corrección de continuidad. */
    static double zScore(double wPlus, double[] shifted) {
        int n = shifted.length;
        double[] abs = Arrays.stream(shifted).map(Math::abs).sorted().toArray();
        double zeros = 0;
        double tieCorrection = 0;
        int i = 0;
        while (i < n) {
            int j = i;
            while (j < n && abs[j] == abs[i]) {
                j++;
            }
            double t = j - i;
            if (abs[i] == 0) {
                zeros = t;
            }
            tieCorrection += t * t * t - t;
            i = j;
        }
        double e = (n * (n + 1.0) - zeros * (zeros + 1.0)) / 4;
        double variance = (n * (n + 1.0) * (2 * n + 1.0) - zeros * (zeros + 1.0) * (2 * zeros + 1.0)
                - tieCorrection / 2) / 24;
        return (wPlus - e) / Math.sqrt(variance);
    }
}
