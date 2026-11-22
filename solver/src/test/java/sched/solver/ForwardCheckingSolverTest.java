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
        assertTrue(solver.solveScored(problem(candidates, 100, SectionCost.ZERO, courses), stats).isEmpty());
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
        assertTrue(solver.solveScored(problem(candidates, 100, SectionCost.ZERO, "X", "Y", "Z"), stats).isEmpty());
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
        assertEquals(1, solver.solveScored(problem(candidates, 1, SectionCost.ZERO, courses), stats).size());
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
}
