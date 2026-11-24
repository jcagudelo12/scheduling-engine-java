package sched.simulation.calibration;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ExecutorService;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sched.simulation.experiment.Calibration;
import sched.simulation.experiment.ExperimentConfig;

class CalibratorTest {

    private static final ExecutorService POOL = Search.newPool(4);
    private static final Calibration CALIBRATION = Calibration.load("");

    @AfterAll
    static void shutdown() {
        POOL.shutdownNow();
    }

    private static ExperimentConfig small(long seed) {
        return ExperimentConfig.defaults().withSeed(seed).withProfiles(40);
    }

    private static Calibrator.Outcome calibrate(long seed) {
        return Calibrator.calibrate(small(seed), CALIBRATION, ParameterSpace.defaults(), 60, POOL);
    }

    /** Una sola calibración compartida por las pruebas que no necesitan una propia (es lo más caro). */
    private static final java.util.concurrent.atomic.AtomicReference<Calibrator.Outcome> SHARED =
            new java.util.concurrent.atomic.AtomicReference<>();

    private static Calibrator.Outcome shared() {
        return SHARED.updateAndGet(o -> o != null ? o : calibrate(3));
    }

    @Test
    void minimalBudgetImprovesOrEqualsTheInitialPoint() {
        Calibrator.Outcome outcome = shared();

        assertTrue(outcome.inSample().total() <= outcome.initial().total() + 1e-12,
                outcome.inSample().total() + " frente a " + outcome.initial().total());
        assertEquals(outcome.search().best().value(), outcome.inSample().total(), 1e-12);
        assertEquals(Calibrator.VALIDATION_SEEDS, outcome.validation().size());
        assertFalse(outcome.validationSeeds().contains(3L));
        assertEquals(ParameterSpace.defaults().size(), outcome.sensitivities().size());
    }

    @Test
    void calibratedValuesRespectTheBounds() {
        Calibrator.Outcome outcome = shared();
        ParameterSpace space = outcome.space();

        for (int i = 0; i < space.size(); i++) {
            ParamSpec spec = space.specs().get(i);
            assertTrue(outcome.values()[i] >= spec.lo() - 1e-12 && outcome.values()[i] <= spec.hi() + 1e-12,
                    spec.name() + " = " + outcome.values()[i]);
            for (String path : spec.paths()) {
                assertEquals(outcome.values()[i], outcome.calibrated().valueAt(path), 1e-9, path);
            }
        }
    }

    @Test
    void sameSeedGivesTheSameCalibration() {
        Calibrator.Outcome a = shared();
        Calibrator.Outcome b = calibrate(3);

        assertArrayEquals(a.values(), b.values(), 0);
        assertEquals(a.inSample().total(), b.inSample().total(), 0);
        assertEquals(a.outOfSample().total(), b.outOfSample().total(), 0);
    }

    @Test
    void writesTheCalibratedConfigThatExperimentCanLoadAndTheReport(@TempDir Path dir) throws Exception {
        Calibrator.Outcome outcome = shared();
        CalibrationCli.write(dir, outcome, "base.json", 60, 1.0);

        ExperimentConfig loaded = ExperimentConfig.load(dir.resolve("calibrated.json"));
        assertEquals(outcome.calibrated(), loaded);
        String md = Files.readString(dir.resolve("calibration.md"));
        assertTrue(md.contains("Hellinger"));
        assertTrue(md.contains("A9") && md.contains("A11") && md.contains("A13"));
        assertTrue(md.contains("no identificables") || md.contains("No identificables")
                || md.contains("Parámetros no identificables"));
        assertTrue(Files.exists(dir.resolve("calibration.json")));
    }

    @Test
    void latinHypercubeCoversEveryStratumOfEveryDimension() {
        var points = Search.latinHypercube(10, 3, new java.util.SplittableRandom(1));
        for (int d = 0; d < 3; d++) {
            boolean[] seen = new boolean[10];
            for (double[] p : points) {
                seen[(int) (p[d] * 10)] = true;
            }
            for (boolean s : seen) {
                assertTrue(s);
            }
        }
    }
}
