package sched.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import sched.domain.Catalog;
import sched.domain.CourseId;
import sched.domain.Preferences;
import sched.domain.Section;
import sched.domain.SectionId;
import sched.domain.SectionSeats;
import sched.domain.StudentContext;
import sched.domain.StudentId;
import sched.domain.TimeSlot;
import sched.domain.Weekday;
import sched.solver.ForwardCheckingSolver;

/** Las alternativas son las de menor (distancia, penalización) según un oráculo de fuerza bruta. */
class SuggestAlternativesPropertiesTest {

    /** Catálogo aleatorio: 1 a 4 cursos, 1 a 3 grupos cada uno, franjas en 3 días y 4 bloques. */
    record Scenario(List<SectionSeats> sections, int courses, long selectionSeed, int avoidMask, int max) {
    }

    @Provide
    Arbitrary<Scenario> scenarios() {
        Arbitrary<Integer> block = Arbitraries.integers().between(0, 11);
        Arbitrary<List<Integer>> perCourse = Arbitraries.integers().between(1, 3).flatMap(n ->
                Arbitraries.integers().between(0, 11).list().ofSize(n));
        return Arbitraries.integers().between(1, 4).flatMap(courses ->
                perCourse.list().ofSize(courses).flatMap(blocks ->
                        Arbitraries.integers().between(0, 3).list().ofSize(courses * 3).flatMap(seats ->
                                Arbitraries.longs().flatMap(seed ->
                                        Arbitraries.integers().between(0, 11).flatMap(mask ->
                                                Arbitraries.integers().between(1, 6).map(max ->
                                                        build(blocks, seats, seed, mask, max)))))));
    }

    private static Scenario build(List<List<Integer>> blocks, List<Integer> seats, long seed, int mask, int max) {
        List<SectionSeats> sections = new ArrayList<>();
        for (int c = 0; c < blocks.size(); c++) {
            for (int g = 0; g < blocks.get(c).size(); g++) {
                sections.add(new SectionSeats(section(c, g, blocks.get(c).get(g)), seats.get(c * 3 + g)));
            }
        }
        return new Scenario(sections, blocks.size(), seed, mask, max);
    }

    private static Section section(int course, int group, int block) {
        Weekday day = Weekday.values()[block / 4];
        int start = 420 + (block % 4) * 60;
        return new Section(new SectionId("C" + course + "-" + group), new CourseId("C" + course),
                List.of(TimeSlot.of(day, start, start + 60).orElseThrow()), 30);
    }

    private record Eval(List<Section> sections, int distance, long penalty) {
    }

    @Property
    void alternativesAreTheLexicographicBest(@ForAll("scenarios") Scenario scenario) throws Exception {
        Catalog catalog = Catalog.load(1, scenario.sections());
        CatalogReplica replica = new CatalogReplica();
        replica.replace(catalog);

        List<CourseId> courses = new ArrayList<>();
        for (int c = 0; c < scenario.courses(); c++) {
            courses.add(new CourseId("C" + c));
        }
        Preferences preferences = new Preferences(List.of(
                TimeSlot.of(Weekday.values()[scenario.avoidMask() / 4], 420 + (scenario.avoidMask() % 4) * 60,
                        480 + (scenario.avoidMask() % 4) * 60).orElseThrow()));
        // Selección pseudoaleatoria: cada curso elige alguno de sus grupos (existan o no cupos) o ninguno.
        java.util.Random random = new java.util.Random(scenario.selectionSeed());
        Map<CourseId, SectionId> selection = new HashMap<>();
        for (CourseId course : courses) {
            List<SectionId> own = scenario.sections().stream().map(s -> s.section())
                    .filter(s -> s.course().equals(course)).map(Section::id).toList();
            int pick = random.nextInt(own.size() + 1);
            if (pick < own.size()) {
                selection.put(course, own.get(pick));
            }
        }

        Alternatives result = new SuggestAlternatives(new ForwardCheckingSolver(), replica, scenario.max())
                .execute(new StudentContext(new StudentId("s"), courses, preferences), selection);

        // Oráculo: todas las combinaciones sin cruces y con cupo, con (distancia, penalización).
        List<Eval> all = new ArrayList<>();
        enumerate(courses, 0, scenario, selection, preferences, new ArrayList<>(), all);
        List<List<Long>> expected = all.stream()
                .map(e -> List.of((long) e.distance(), e.penalty()))
                .sorted((a, b) -> a.get(0) != b.get(0).longValue()
                        ? Long.compare(a.get(0), b.get(0)) : Long.compare(a.get(1), b.get(1)))
                .limit(scenario.max())
                .toList();
        List<List<Long>> actual = result.alternatives().stream()
                .map(a -> List.of((long) a.distance(), a.penalty()))
                .toList();

        assertEquals(expected, actual);
        for (Alternatives.Alternative alternative : result.alternatives()) {
            assertFalse(alternative.schedule().hasClashes());
            assertEquals(courses.size(), alternative.schedule().sections().size());
            for (Section section : alternative.schedule().sections()) {
                assertTrue(catalog.available(section.id()).orElse(0) > 0);
            }
        }
    }

    private static void enumerate(List<CourseId> courses, int index, Scenario scenario,
            Map<CourseId, SectionId> selection, Preferences preferences, List<Section> current, List<Eval> out) {
        if (index == courses.size()) {
            int distance = 0;
            long penalty = 0;
            for (Section section : current) {
                SectionId chosen = selection.get(section.course());
                if (chosen != null && !chosen.equals(section.id())) {
                    distance++;
                }
                penalty += preferences.penaltyOf(section);
            }
            out.add(new Eval(List.copyOf(current), distance, penalty));
            return;
        }
        for (SectionSeats candidate : scenario.sections()) {
            Section section = candidate.section();
            if (!section.course().equals(courses.get(index)) || candidate.available() <= 0
                    || current.stream().anyMatch(section::clashesWith)) {
                continue;
            }
            current.add(section);
            enumerate(courses, index + 1, scenario, selection, preferences, current, out);
            current.removeLast();
        }
    }
}
