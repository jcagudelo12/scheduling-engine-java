package sched.simulation.calibration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class DistanceTest {

    @Test
    void hellingerOfIdenticalDistributionsIsZeroEvenWithDifferentScales() {
        assertEquals(0, Distance.hellinger(new double[] {50, 30, 20}, new double[] {0.5, 0.3, 0.2}), 1e-12);
    }

    @Test
    void hellingerOfDisjointDistributionsIsOne() {
        assertEquals(1, Distance.hellinger(new double[] {1, 0}, new double[] {0, 1}), 1e-12);
    }

    @Test
    void hellingerKnownCase() {
        // (raíz .5 - 1)^2 + (raíz .5 - 0)^2 = 0,5858; / 2 = 0,2929; raíz = 0,5412.
        assertEquals(0.5412, Distance.hellinger(new double[] {0.5, 0.5}, new double[] {1, 0}), 1e-4);
    }

    @Test
    void hellingerIsSymmetricAndDefinedWithEmptyCategories() {
        double[] p = {0.7, 0.3, 0};
        double[] q = {0.2, 0.3, 0.5};
        assertEquals(Distance.hellinger(p, q), Distance.hellinger(q, p), 1e-15);
        assertTrue(Double.isFinite(Distance.hellinger(p, q)));
    }

    @Test
    void symmetricChiSquareKnownCases() {
        assertEquals(0, Distance.chiSquareSymmetric(new double[] {1, 1}, new double[] {2, 2}), 1e-12);
        // (0,5 - 1)^2 / 1,5 + (0,5 - 0)^2 / 0,5 = 0,1667 + 0,5.
        assertEquals(2.0 / 3, Distance.chiSquareSymmetric(new double[] {0.5, 0.5}, new double[] {1, 0}), 1e-12);
        assertEquals(2, Distance.chiSquareSymmetric(new double[] {1, 0}, new double[] {0, 1}), 1e-12);
    }

    @Test
    void invalidInputsAreRefused() {
        assertThrows(IllegalArgumentException.class, () -> Distance.hellinger(new double[] {1}, new double[] {1, 2}));
        assertThrows(IllegalArgumentException.class, () -> Distance.hellinger(new double[] {0, 0}, new double[] {1, 2}));
        assertThrows(IllegalArgumentException.class, () -> Distance.hellinger(new double[] {-1, 2}, new double[] {1, 2}));
    }

    @Test
    void samplingFloorShrinksWithTheSampleSizeAndIsReproducible() {
        double[] target = {11.9, 15.6, 30.3, 23.0, 1.5, 17.8};
        double small = Distance.samplingFloor(target, 135, 300, 1);
        double large = Distance.samplingFloor(target, 5000, 300, 1);
        assertTrue(small > large);
        assertTrue(small > 0.02 && small < 0.2);
        assertEquals(small, Distance.samplingFloor(target, 135, 300, 1), 0);
    }
}
