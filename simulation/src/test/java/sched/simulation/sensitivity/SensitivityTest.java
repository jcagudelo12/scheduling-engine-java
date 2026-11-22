package sched.simulation.sensitivity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sched.simulation.experiment.ExperimentConfig;

class SensitivityTest {

    /** Corrida chica y exactamente reproducible: el tiempo del motor no se suma. */
    private static ExperimentConfig small() {
        ExperimentConfig base = ExperimentConfig.defaults().withSeed(5).withProfiles(40);
        ExperimentConfig.EngineParams e = base.engine();
        return base.withEngine(new ExperimentConfig.EngineParams(e.solver(), e.maxResults(), e.maxAlternatives(),
                "zero", e.pickBestProbability(), e.pickTop(), e.usePreferences()));
    }

    @Test
    void twoVariationsProduceTheReport(@TempDir Path dir) throws Exception {
        List<Variation> two = Variations.standard().stream()
                .filter(v -> v.id().equals("base") || v.id().equals("b-own-x3")).toList();
        assertEquals(2, two.size());
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            SensitivityCli.Outcome outcome = SensitivityCli.run(small(), two, 2, pool);
            SensitivityCli.write(dir, outcome, "base.json", 1.0);

            assertEquals(2, outcome.rows().size());
            // Triplicar el tiempo propio de B no puede mejorar la reducción.
            assertTrue(outcome.rows().get(1).meanReduction() <= outcome.rows().get(0).meanReduction());
            assertEquals(2, outcome.replicas().count());
            assertTrue(outcome.recommendedProfiles() >= 500);
        } finally {
            pool.shutdownNow();
        }
        String md = Files.readString(dir.resolve("sensitivity.md"));
        assertTrue(md.contains("Punto de quiebre"));
        assertTrue(md.contains("Réplicas"));
        assertTrue(md.contains("Tamaño de muestra"));
        assertTrue(md.contains("b-own-x3"));
        assertTrue(Files.readString(dir.resolve("sensitivity.json")).contains("\"breakpoint\""));
    }

    @Test
    void scalingBTimesNeverTouchesTheSharedOnes() {
        ExperimentConfig base = small();
        ExperimentConfig scaled = Variations.scale(base, 3, Variations.B_OWN_TIMES);

        assertEquals(3 * base.times().reviewCombination().meanSeconds(), scaled.times().reviewCombination().meanSeconds(), 1e-9);
        assertEquals(base.times().browseCourse(), scaled.times().browseCourse());
        assertEquals(base.times().submitSection(), scaled.times().submitSection());
    }

    @Test
    void breakpointFactorBringsTheReductionDownToTheThreshold() {
        ExperimentConfig base = small();
        SensitivityRunner.Breakpoint b = SensitivityRunner.breakpoint(base, 0.20);

        assertTrue(b.found() || b.beyondMax());
        if (b.found() && b.factor() > 1) {
            assertTrue(SensitivityRunner.meanReduction(base, b.factor() * 1.05) <= 0.2 + 0.03);
            assertTrue(SensitivityRunner.meanReduction(base, b.factor() / 1.05) >= 0.2 - 0.03);
        }
    }
}
