package sched.simulation.experiment;

import java.util.List;
import java.util.SplittableRandom;
import java.util.random.RandomGenerator;

/**
 * Flujos de aleatoriedad independientes derivados de una sola semilla.
 *
 * <p>Cada flujo (oferta, perfiles, llegadas, comportamiento de cada estudiante) sale de
 * {@code (semilla, nombre, índice)}, de modo que cambiar cuánto consume uno no altera a los
 * demás. Así las llegadas y los perfiles son idénticos en las dos condiciones.
 */
final class Seeds {

    private static final long GOLDEN = 0x9E3779B97F4A7C15L;

    private Seeds() {
    }

    /** Mezcla de 64 bits (splitmix64). */
    static long mix(long z) {
        z += GOLDEN;
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    static long derive(long seed, String stream, long index) {
        // String.hashCode está especificado, así que es estable entre ejecuciones y JVM.
        return mix(mix(seed ^ mix(stream.hashCode())) + index * GOLDEN);
    }

    static SplittableRandom rng(long seed, String stream, long index) {
        return new SplittableRandom(derive(seed, stream, index));
    }

    /** Índice elegido con probabilidad proporcional a los pesos (no negativos, suma > 0). */
    static int pick(RandomGenerator random, double[] weights) {
        double total = 0;
        for (double w : weights) {
            total += w;
        }
        if (total <= 0) {
            throw new IllegalArgumentException("los pesos deben sumar más que cero");
        }
        double x = random.nextDouble() * total;
        for (int i = 0; i < weights.length; i++) {
            x -= weights[i];
            if (x < 0) {
                return i;
            }
        }
        return weights.length - 1;
    }

    /** Gamma(forma, 1) por Marsaglia-Tsang (para forma < 1 se usa la elevación de forma + 1). */
    static double gamma(RandomGenerator random, double shape) {
        if (shape < 1) {
            return gamma(random, shape + 1) * Math.pow(1 - random.nextDouble(), 1 / shape);
        }
        double d = shape - 1.0 / 3;
        double c = 1 / Math.sqrt(9 * d);
        while (true) {
            double x = random.nextGaussian();
            double v = 1 + c * x;
            if (v <= 0) {
                continue;
            }
            v = v * v * v;
            double u = 1 - random.nextDouble();
            if (Math.log(u) < 0.5 * x * x + d - d * v + d * Math.log(v)) {
                return d * v;
            }
        }
    }

    /** Beta con la media y la concentración (a + b) dadas; una media en el borde da ese borde. */
    static double beta(RandomGenerator random, double mean, double concentration) {
        if (mean <= 0) {
            return 0;
        }
        if (mean >= 1) {
            return 1;
        }
        double x = gamma(random, mean * concentration);
        double y = gamma(random, (1 - mean) * concentration);
        return x / (x + y);
    }

    static double[] toArray(List<Double> values) {
        return values.stream().mapToDouble(Double::doubleValue).toArray();
    }
}
