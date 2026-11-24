package sched.simulation.analysis;

import org.apache.commons.statistics.distribution.NormalDistribution;

/**
 * Potencia y tamaño de muestra de la prueba principal (H1: media de r &gt; mu0, unilateral), con la
 * aproximación normal de una muestra: con desviación estándar sigma de r y efecto mínimo de
 * interés delta (puntos sobre mu0), n = ((z(1-alfa) + z(potencia)) * sigma / delta)^2 y
 * potencia = Phi(delta * raíz(n) / sigma - z(1-alfa)). Se suma la corrección de Guenther
 * (z(1-alfa)^2 / 2) para acercarse a la t. Es una aproximación: sirve para justificar el orden de
 * magnitud del número de perfiles, no para un cálculo exacto (con Wilcoxon la potencia es parecida).
 */
public final class Power {

    /** Efecto mínimo de interés por defecto: 5 puntos porcentuales sobre mu0. */
    public static final double MIN_EFFECT = 0.05;
    private static final NormalDistribution NORMAL = NormalDistribution.of(0, 1);

    private Power() {
    }

    public record Check(double sd, double delta, double alpha, int requiredFor80, int requiredFor90, int nUsed,
                        double powerAtN) {
    }

    public static int requiredN(double sd, double delta, double alpha, double power) {
        double zAlpha = NORMAL.inverseCumulativeProbability(1 - alpha);
        double zPower = NORMAL.inverseCumulativeProbability(power);
        double n = Math.pow((zAlpha + zPower) * sd / delta, 2) + zAlpha * zAlpha / 2;
        return (int) Math.ceil(n);
    }

    public static double power(int n, double sd, double delta, double alpha) {
        double zAlpha = NORMAL.inverseCumulativeProbability(1 - alpha);
        return NORMAL.cumulativeProbability(delta * Math.sqrt(n) / sd - zAlpha);
    }

    public static Check check(double sd, int nUsed, double alpha, double delta) {
        return new Check(sd, delta, alpha, requiredN(sd, delta, alpha, 0.80), requiredN(sd, delta, alpha, 0.90),
                nUsed, power(nUsed, sd, delta, alpha));
    }
}
