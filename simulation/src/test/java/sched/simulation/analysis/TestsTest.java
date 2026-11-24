package sched.simulation.analysis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import sched.simulation.analysis.Tests.Alternative;
import sched.simulation.analysis.Tests.Outcome;

class TestsTest {

    /** Función de distribución de la t con 4 gl (fórmula cerrada). */
    private static double cdfT4(double t) {
        double s2 = 1 + t * t / 4;
        return 0.5 + 0.375 * (t / Math.sqrt(s2)) * (1 - t * t / (12 * s2));
    }

    @Test
    void tDeUnaMuestraCoincideConLaFormulaCerrada() {
        double[] x = {1, 2, 3, 4, 5};
        Outcome o = Tests.tTest(x, 2, Alternative.GREATER);
        double t = (3 - 2) / (Math.sqrt(2.5) / Math.sqrt(5)); // 1.4142
        assertEquals(t, o.statistic(), 1e-12);
        assertEquals(4, o.df(), 0);
        assertEquals(1 - cdfT4(t), o.pValue(), 1e-9);
        assertEquals((3 - 2) / Math.sqrt(2.5), o.effectSize(), 1e-12); // d de Cohen
        assertEquals(2 * (1 - cdfT4(t)), Tests.tTest(x, 2, Alternative.TWO_SIDED).pValue(), 1e-9);
        assertEquals(cdfT4(t), Tests.tTest(x, 2, Alternative.LESS).pValue(), 1e-9);
    }

    @Test
    void wilcoxonExactoSinEmpatesNiCeros() {
        // rangos de |x|: 1,2,3,4,5; W+ = 1 + 3 + 4 + 5 = 13; P(W+ >= 13) = 3/32 (subconjuntos con suma <= 2)
        double[] x = {1.5, -2.5, 3.5, 4.5, 5.5};
        Outcome o = Tests.wilcoxon(x, 0, Alternative.GREATER);
        assertEquals(13, o.statistic(), 0);
        assertEquals(3.0 / 32, o.pValue(), 1e-12);
        assertEquals(6.0 / 32, Tests.wilcoxon(x, 0, Alternative.TWO_SIDED).pValue(), 1e-12);
        double z = (13 - 7.5) / Math.sqrt(5 * 6 * 11 / 24.0);
        assertEquals(z, o.z(), 1e-12);
        assertEquals(z / Math.sqrt(5), o.effectSize(), 1e-12);
        assertEquals("exacto", o.method());
    }

    @Test
    void wilcoxonRestaMuAntesDeRankear() {
        double[] x = {1.7, -2.3, 3.7, 4.7, 5.7}; // x - 0.2 = el caso anterior
        assertEquals(3.0 / 32, Tests.wilcoxon(x, 0.2, Alternative.GREATER).pValue(), 1e-12);
    }

    @Test
    void wilcoxonConEmpatesUsaLaAproximacionNormal() {
        double[] x = {1, 1, 2, 3, -3, 4, 5, 6};
        Outcome o = Tests.wilcoxon(x, 0, Alternative.GREATER);
        assertTrue(o.method().startsWith("aproximación normal"));
        // |x| ordenados: 1,1,2,3,3,4,5,6 -> rangos 1.5,1.5,3,4.5,4.5,6,7,8; W+ = 36 - 4.5 = 31.5
        assertEquals(31.5, o.statistic(), 1e-12);
        double c = 6 + 6; // empates de 2: (8 - 2) cada uno
        double var = (8 * 9 * 17 - c / 2) / 24;
        assertEquals((31.5 - 18) / Math.sqrt(var), o.z(), 1e-12);
    }

    @Test
    void todasLasDiferenciasIgualesAMuNoDanEvidencia() {
        Outcome o = Tests.wilcoxon(new double[] {0.2, 0.2, 0.2}, 0.2, Alternative.GREATER);
        assertEquals(1.0, o.pValue(), 0);
        assertEquals(0.0, o.effectSize(), 0);
    }
}
