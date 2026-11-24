package sched.simulation.analysis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Random;
import org.junit.jupiter.api.Test;

class ShapiroWilkTest {

    @Test
    void coincideConElValorDeReferenciaDeR() {
        // R: shapiro.test(c(148,154,158,160,161,162,166,170,182,195,236)) -> W = 0.7888, p-value = 0.0067
        ShapiroWilk.Result r = ShapiroWilk.test(new double[] {148, 154, 158, 160, 161, 162, 166, 170, 182, 195, 236});
        assertEquals(0.7888, r.w(), 1e-3);
        assertEquals(0.0067, r.pValue(), 1e-3);
        assertFalse(r.normal(0.05));
    }

    @Test
    void muestrasNormalesNoRechazanEnLaGranMayoria() {
        Random random = new Random(12345);
        int notRejected = 0;
        for (int rep = 0; rep < 200; rep++) {
            double[] x = new double[100];
            for (int i = 0; i < x.length; i++) {
                x[i] = random.nextGaussian();
            }
            if (ShapiroWilk.test(x).pValue() > 0.05) {
                notRejected++;
            }
        }
        // Esperado ~95 % (190 de 200); margen amplio para no depender de la semilla.
        assertTrue(notRejected >= 180 && notRejected <= 199, "no rechazadas: " + notRejected);
    }

    @Test
    void muestrasExponencialesGrandesSeRechazan() {
        Random random = new Random(7);
        double[] x = new double[200];
        for (int i = 0; i < x.length; i++) {
            x[i] = -Math.log(1 - random.nextDouble());
        }
        assertTrue(ShapiroWilk.test(x).pValue() < 0.001);
    }

    @Test
    void nFueraDeRangoLanzaExcepcion() {
        assertThrows(IllegalArgumentException.class, () -> ShapiroWilk.test(new double[] {1, 2}));
        assertThrows(IllegalArgumentException.class, () -> ShapiroWilk.test(new double[5001]));
    }

    @Test
    void muestraConstanteSeMarcaYNoEsNormal() {
        ShapiroWilk.Result r = ShapiroWilk.test(new double[] {3, 3, 3, 3, 3});
        assertTrue(r.constant());
        assertTrue(Double.isNaN(r.pValue()));
        assertFalse(r.normal(0.05));
    }

    @Test
    void conTresDatosElPValorEsExacto() {
        ShapiroWilk.Result r = ShapiroWilk.test(new double[] {1, 2, 3});
        assertEquals(1.0, r.w(), 1e-12); // perfectamente lineal
        assertEquals(1.0, r.pValue(), 1e-6);
    }

    @Test
    void conMasDe5000DatosSubmuestreaConSemillaFija() {
        Random random = new Random(1);
        double[] x = new double[6000];
        for (int i = 0; i < x.length; i++) {
            x[i] = random.nextGaussian();
        }
        ShapiroWilk.Result a = ShapiroWilk.testSubsampling(x, 42);
        ShapiroWilk.Result b = ShapiroWilk.testSubsampling(x, 42);
        assertTrue(a.subsampled());
        assertEquals(5000, a.nUsed());
        assertEquals(6000, a.n());
        assertEquals(a.pValue(), b.pValue());
    }
}
