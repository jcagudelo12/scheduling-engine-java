package sched.simulation;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SimulationTest {

    private static final Path SAMPLE = Path.of("fixtures/sample.json");

    @Test
    void loadsTheSampleScenario() {
        Scenario scenario = Scenario.load(SAMPLE);

        assertEquals(7, scenario.sections().size());
        assertEquals(3, scenario.students().size());
        assertEquals(0, scenario.sections().stream()
                .filter(s -> s.section().id().value().equals("PRG101-02"))
                .findFirst().orElseThrow().available());
    }

    @Test
    void sampleScenarioYieldsExpectedCombinations() throws Exception {
        Map<String, Simulation.Result> results = Simulation.run(Scenario.load(SAMPLE));

        // PRG101-02 está lleno, MAT101-01 choca con FIS101-01 (lunes) y FIS101-02 con ING101-01
        // (viernes).
        assertEquals(3, results.get("est-001").combinations());
        assertEquals(2, results.get("est-002").combinations());
        assertEquals(1, results.get("est-003").combinations());
    }
}
