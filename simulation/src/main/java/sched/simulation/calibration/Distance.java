package sched.simulation.calibration;

import java.util.SplittableRandom;

/**
 * Distancias entre distribuciones categóricas, dadas como porcentajes (se normalizan a suma 1).
 *
 * <p>La calibración usa la <b>distancia de Hellinger</b>, H(p, q) = (1/raíz 2) * raíz(suma (raíz p_i -
 * raíz q_i)^2), por tres razones: (1) está acotada en [0, 1] y es simétrica, así que las tres tablas
 * (6, 5 y 3 categorías) pesan en la misma escala y se suman con pesos; (2) está bien definida con
 * categorías de probabilidad 0 (la chi-cuadrado con una frecuencia esperada 0 se dispara, y el
 * flujo A produce a veces 0 % en categorías de cola); (3) la raíz da más peso relativo a las
 * categorías pequeñas (4,4 % de "más de 2 h", 1,5 % de "4 veces") que la diferencia absoluta. La
 * chi-cuadrado simétrica se ofrece para contrastar.
 */
public final class Distance {

    private Distance() {
    }

    /** Hellinger entre dos vectores no negativos (se normalizan a suma 1); en [0, 1]. */
    public static double hellinger(double[] p, double[] q) {
        check(p, q);
        double[] a = normalized(p);
        double[] b = normalized(q);
        double sum = 0;
        for (int i = 0; i < a.length; i++) {
            double d = Math.sqrt(a[i]) - Math.sqrt(b[i]);
            sum += d * d;
        }
        return Math.sqrt(sum / 2);
    }

    /**
     * Chi-cuadrado simétrica: suma (p_i - q_i)^2 / (p_i + q_i) sobre las categorías con p_i + q_i &gt; 0
     * (las vacías en ambas no aportan). Está en [0, 2] con distribuciones de suma 1.
     */
    public static double chiSquareSymmetric(double[] p, double[] q) {
        check(p, q);
        double[] a = normalized(p);
        double[] b = normalized(q);
        double sum = 0;
        for (int i = 0; i < a.length; i++) {
            double total = a[i] + b[i];
            if (total > 0) {
                double d = a[i] - b[i];
                sum += d * d / total;
            }
        }
        return sum;
    }

    /**
     * Distancia de Hellinger esperada entre una muestra de tamaño {@code n} de {@code target} y
     * {@code target}: el piso de ruido de comparar contra una consulta de n estudiantes. Una
     * simulación no puede razonablemente quedar más cerca que esto de lo que quedaría la propia
     * distribución verdadera frente a otra muestra de la consulta.
     */
    public static double samplingFloor(double[] target, int n, int draws, long seed) {
        double[] p = normalized(target);
        SplittableRandom random = new SplittableRandom(seed);
        double total = 0;
        for (int d = 0; d < draws; d++) {
            double[] counts = new double[p.length];
            for (int i = 0; i < n; i++) {
                double x = random.nextDouble();
                int k = 0;
                while (k < p.length - 1 && (x -= p[k]) >= 0) {
                    k++;
                }
                counts[k]++;
            }
            total += hellinger(counts, p);
        }
        return total / draws;
    }

    static double[] normalized(double[] x) {
        double sum = 0;
        for (double v : x) {
            if (v < 0) {
                throw new IllegalArgumentException("las frecuencias no pueden ser negativas");
            }
            sum += v;
        }
        if (sum <= 0) {
            throw new IllegalArgumentException("la distribución debe sumar más que cero");
        }
        double[] out = new double[x.length];
        for (int i = 0; i < x.length; i++) {
            out[i] = x[i] / sum;
        }
        return out;
    }

    private static void check(double[] p, double[] q) {
        if (p.length != q.length || p.length == 0) {
            throw new IllegalArgumentException("las distribuciones deben tener las mismas categorías");
        }
    }
}
