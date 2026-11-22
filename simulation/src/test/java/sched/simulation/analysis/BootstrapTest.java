package sched.simulation.analysis;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Random;
import org.junit.jupiter.api.Test;

class BootstrapTest {

    @Test
    void esDeterministaConLaMismaSemilla() {
        double[] x = {0.1, 0.4, 0.35, 0.6, 0.55, 0.2, 0.9};
        double[] a = Bootstrap.meanConfidenceInterval(x, 0.95, 10_000, 42);
        double[] b = Bootstrap.meanConfidenceInterval(x, 0.95, 10_000, 42);
        assertArrayEquals(a, b);
        assertTrue(a[0] < Descriptive.mean(x) && Descriptive.mean(x) < a[1]);
        assertTrue(a[0] >= 0.1 && a[1] <= 0.9);
    }

    @Test
    void conDatosConstantesElIntervaloEsElValor() {
        double[] ci = Bootstrap.meanConfidenceInterval(new double[] {0.5, 0.5, 0.5}, 0.95, 1000, 1);
        assertEquals(0.5, ci[0], 1e-12);
        assertEquals(0.5, ci[1], 1e-12);
    }

    @Test
    void secercaAlIntervaloTEnMuestrasNormalesGrandes() {
        Random random = new Random(3);
        double[] x = new double[400];
        for (int i = 0; i < x.length; i++) {
            x[i] = 5 + 2 * random.nextGaussian();
        }
        double[] boot = Bootstrap.meanConfidenceInterval(x, 0.95, 10_000, 42);
        double[] t = Descriptive.meanConfidenceInterval(x, 0.95);
        assertEquals(t[0], boot[0], 0.03);
        assertEquals(t[1], boot[1], 0.03);
    }
}
