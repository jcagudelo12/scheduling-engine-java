package sched.solver;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import sched.domain.CourseId;
import sched.domain.Section;
import sched.domain.SectionId;
import sched.domain.TimeSlot;
import sched.domain.Weekday;

class ForwardCheckingSolverTest {

    private final ForwardCheckingSolver solver = new ForwardCheckingSolver();

    private static Section section(String id, String course, Weekday day, int start, int end) {
        return new Section(new SectionId(id), new CourseId(course),
                List.of(TimeSlot.of(day, start, end).orElseThrow()), 30);
    }

    private static Problem problem(List<Section> candidates, int max, SectionCost cost, String... courses) {
        return new Problem(Arrays.stream(courses).map(CourseId::new).toList(), candidates, max, cost);
    }

    private static List<String> ids(Scored s) {
        return s.schedule().sections().stream().map(x -> x.id().value()).toList();
    }

    @Test
    void noSolutionIsDetectedAtTheRoot() {
        // El último curso choca con todo: tras asignar cualquier grupo del primero, su dominio queda vacío.
        List<Section> candidates = new ArrayList<>();
        String[] courses = new String[7];
        for (int c = 0; c < 7; c++) {
            courses[c] = "C" + c;
            for (int s = 0; s < 8; s++) {
                if (c < 6) {
                    candidates.add(section("C" + c + "-" + s, "C" + c, Weekday.values()[s % 7], 360 + c * 60, 420 + c * 60));
                } else {
                    candidates.add(new Section(new SectionId("C6-" + s), new CourseId("C6"),
                            Arrays.stream(Weekday.values())
                                    .map(d -> TimeSlot.of(d, 360, 1320).orElseThrow()).toList(), 30));
                }
            }
        }
        ForwardCheckingSolver.Stats stats = new ForwardCheckingSolver.Stats();
        assertTrue(solver.search(problem(candidates, 100, SectionCost.ZERO, courses), stats).results().isEmpty());
        assertTrue(stats.nodes < 10, "nodos: " + stats.nodes);
    }

    @Test
    void mrvAssignsTheMostConstrainedCourseFirst() {
        // Z tiene un solo grupo y choca con todos los de Y: con MRV se descubre en la raíz.
        List<Section> candidates = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            candidates.add(section("X" + i, "X", Weekday.MONDAY, 360 + i * 60, 420 + i * 60));
            candidates.add(section("Y" + i, "Y", Weekday.TUESDAY, 360 + i * 60, 420 + i * 60));
        }
        candidates.add(section("Z0", "Z", Weekday.TUESDAY, 360, 600));

        ForwardCheckingSolver.Stats stats = new ForwardCheckingSolver.Stats();
        assertTrue(solver.search(problem(candidates, 100, SectionCost.ZERO, "X", "Y", "Z"), stats).results().isEmpty());
        assertEquals(1, stats.nodes);
    }

    @Test
    void schedulesFollowRequestOrderEvenWhenMrvAssignsInAnotherOrder() {
        List<Section> candidates = List.of(
                section("X0", "X", Weekday.MONDAY, 360, 420),
                section("X1", "X", Weekday.MONDAY, 420, 480),
                section("Y0", "Y", Weekday.TUESDAY, 360, 420),
                section("Z0", "Z", Weekday.WEDNESDAY, 360, 420));
        List<Scored> result = solver.solveScored(problem(candidates, 10, SectionCost.ZERO, "X", "Y", "Z"));
        assertEquals(List.of(List.of("X0", "Y0", "Z0"), List.of("X1", "Y0", "Z0")), result.stream().map(ForwardCheckingSolverTest::ids).toList());
    }

    @Test
    void tiesAreOrderedBySectionIds() {
        List<Section> candidates = List.of(
                section("A2", "A", Weekday.MONDAY, 360, 420),
                section("A1", "A", Weekday.TUESDAY, 360, 420),
                section("B2", "B", Weekday.WEDNESDAY, 360, 420),
                section("B1", "B", Weekday.THURSDAY, 360, 420));
        List<Scored> result = solver.solveScored(problem(candidates, 10, SectionCost.ZERO, "A", "B"));
        assertEquals(
                List.of(List.of("A1", "B1"), List.of("A1", "B2"), List.of("A2", "B1"), List.of("A2", "B2")),
                result.stream().map(ForwardCheckingSolverTest::ids).toList());
        assertTrue(result.stream().allMatch(s -> s.cost() == 0));
    }

    @Test
    void withoutCostStopsAsSoonAsItHasMaxResults() {
        List<Section> candidates = new ArrayList<>();
        String[] courses = new String[5];
        for (int c = 0; c < 5; c++) {
            courses[c] = "C" + c;
            for (int s = 0; s < 5; s++) {
                candidates.add(section("C" + c + "-" + s, "C" + c, Weekday.values()[s], 360 + c * 60, 420 + c * 60));
            }
        }
        ForwardCheckingSolver.Stats stats = new ForwardCheckingSolver.Stats();
        assertEquals(1, solver.search(problem(candidates, 1, SectionCost.ZERO, courses), stats).results().size());
        assertEquals(6, stats.nodes); // raíz + 5 niveles, sin retroceso
    }

    @Test
    void returnsTheCheapestCombinationsFirst() {
        List<Section> candidates = List.of(
                section("A1", "A", Weekday.MONDAY, 360, 420),
                section("A2", "A", Weekday.TUESDAY, 360, 420),
                section("B1", "B", Weekday.MONDAY, 400, 460), // choca con A1
                section("B2", "B", Weekday.WEDNESDAY, 360, 420));
        SectionCost cost = s -> switch (s.id().value()) {
            case "A1" -> 1;
            case "A2" -> 5;
            case "B1" -> 0;
            default -> 3;
        };
        List<Scored> result = solver.solveScored(problem(candidates, 2, cost, "A", "B"));
        assertEquals(List.of(List.of("A1", "B2"), List.of("A2", "B1")), result.stream().map(ForwardCheckingSolverTest::ids).toList());
        assertEquals(List.of(4L, 5L), result.stream().map(Scored::cost).toList());
    }

    @Test
    void negativeCostIsRejected() {
        List<Section> candidates = List.of(section("A1", "A", Weekday.MONDAY, 360, 420));
        assertThrows(IllegalArgumentException.class,
                () -> solver.solveScored(problem(candidates, 1, s -> -1, "A")));
    }

    @Test
    void courseWithoutSectionsYieldsNothing() {
        List<Section> candidates = List.of(section("A1", "A", Weekday.MONDAY, 360, 420));
        assertTrue(solver.solve(problem(candidates, 5, SectionCost.ZERO, "A", "B")).isEmpty());
    }

    @Test
    void withoutCostAndSmallMaxResultsReturnsKSortedCombinations() {
        // 3 cursos sin cruces, 3 grupos cada uno: 27 combinaciones; se piden 4 y salen ordenadas por ids.
        List<Section> candidates = new ArrayList<>();
        for (int c = 0; c < 3; c++) {
            for (int s = 2; s >= 0; s--) {
                candidates.add(section("C" + c + "-" + s, "C" + c, Weekday.values()[s], 360 + c * 120, 420 + c * 120));
            }
        }
        SearchResult result = solver.search(problem(candidates, 4, SectionCost.ZERO, "C0", "C1", "C2"));
        assertTrue(result.complete());
        List<List<String>> got = result.results().stream().map(ForwardCheckingSolverTest::ids).toList();
        assertEquals(4, got.size());
        for (int i = 1; i < got.size(); i++) {
            assertTrue(String.join(",", got.get(i - 1)).compareTo(String.join(",", got.get(i))) < 0);
        }
    }

    @Test
    void repeatedSectionIdsDoNotBreakOrdering() {
        // Dos grupos del mismo curso con el mismo id y distinto horario: mismo rango, sin excepciones.
        List<Section> candidates = List.of(
                section("A-1", "A", Weekday.MONDAY, 360, 420),
                section("A-1", "A", Weekday.TUESDAY, 360, 420),
                section("B-1", "B", Weekday.MONDAY, 360, 420),
                section("B-2", "B", Weekday.WEDNESDAY, 360, 420));
        List<Scored> result = solver.search(problem(candidates, 10, section -> 0L, "A", "B")).results();
        assertEquals(3, result.size());
        assertEquals(List.of("A-1", "B-1"), ids(result.get(0)));
        assertEquals(List.of("A-1", "B-2"), ids(result.get(1)));
        assertEquals(List.of("A-1", "B-2"), ids(result.get(2)));
    }
}
