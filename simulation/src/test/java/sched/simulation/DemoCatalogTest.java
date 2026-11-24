package sched.simulation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sched.simulation.experiment.DemoCatalog;

class DemoCatalogTest {

    @TempDir
    Path dir;

    @Test
    void exportsAScenarioThatTheSimulatorCanLoadWithCreditsAndTheLimit() throws Exception {
        Path out = dir.resolve("sub/demo.json");

        DemoCatalog.write(out, 7);
        Scenario scenario = Scenario.load(out);

        assertFalse(scenario.sections().isEmpty());
        assertFalse(scenario.courses().isEmpty());
        assertTrue(scenario.rules().maxCredits() > 0);
        assertEquals(8, scenario.students().size());
        assertTrue(scenario.courses().stream().allMatch(c -> c.credits() > 0));
        assertTrue(scenario.sections().stream().allMatch(s -> !s.section().slots().isEmpty()));
        // Los grupos de los estudiantes de ejemplo existen en la oferta.
        var offered = scenario.sections().stream().map(s -> s.section().course()).collect(java.util.stream.Collectors.toSet());
        scenario.students().forEach(s -> assertTrue(offered.containsAll(s.eligibleCourses())));
    }

    @Test
    void sameSeedGivesTheSameFileAndAnotherSeedDiffers() throws Exception {
        DemoCatalog.write(dir.resolve("a.json"), 1);
        DemoCatalog.write(dir.resolve("b.json"), 1);
        DemoCatalog.write(dir.resolve("c.json"), 2);

        assertEquals(Files.readString(dir.resolve("a.json")), Files.readString(dir.resolve("b.json")));
        assertFalse(Files.readString(dir.resolve("a.json")).equals(Files.readString(dir.resolve("c.json"))));
    }
}
