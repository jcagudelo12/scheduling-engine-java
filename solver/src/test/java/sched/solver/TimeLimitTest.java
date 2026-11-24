package sched.solver;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import sched.domain.CourseId;
import sched.domain.Schedule;
import sched.domain.Section;
import sched.domain.SectionId;
import sched.domain.TimeSlot;
import sched.domain.Weekday;

/** Tiempo máximo de la búsqueda (ADR 0009): corta los casos que explotan y marca el resultado. */
class TimeLimitTest {

    private static List<CourseId> courses(int count) {
        List<CourseId> courses = new ArrayList<>();
        for (int c = 0; c < count; c++) {
            courses.add(new CourseId("C" + c));
        }
        return courses;
    }

    /**
     * Principio del palomar: cada curso tiene {@code perCourse} grupos y dos grupos de cursos
     * distintos se cruzan si tienen el mismo índice. Una combinación exige índices distintos, así
     * que con más cursos que grupos no hay solución. Los conflictos son por pares y todos los
     * dominios encogen igual, de modo que ni MRV ni forward checking lo ven hasta el nivel
     * {@code perCourse}: el árbol tiene del orden de perCourse! nodos.
     */
    private static List<Section> pigeonhole(int courseCount, int perCourse) {
        List<Section> sections = new ArrayList<>();
        for (int c = 0; c < courseCount; c++) {
            for (int s = 0; s < perCourse; s++) {
                sections.add(new Section(new SectionId("C" + c + "-" + s), new CourseId("C" + c),
                        List.of(TimeSlot.of(Weekday.MONDAY, 360 + s * 60, 420 + s * 60).orElseThrow()), 30));
            }
        }
        return sections;
    }

    private static List<Solver> solvers() {
        return List.of(new ForwardCheckingSolver(), new BacktrackingSolver());
    }

    @Test
    void anAdversarialProblemIsCutAtTheLimitAndMarkedIncomplete() {
        Problem problem = new Problem(
                courses(12), pigeonhole(12, 10), 5, SectionCost.ZERO, Duration.ofMillis(50));
        for (Solver solver : solvers()) {
            long start = System.nanoTime();
            SearchResult result = solver.search(problem);
            long elapsedMs = (System.nanoTime() - start) / 1_000_000;

            assertFalse(result.complete(), solver.getClass().getSimpleName());
            assertTrue(result.results().isEmpty());
            assertTrue(elapsedMs < 200, solver.getClass().getSimpleName() + " tardó " + elapsedMs + " ms");
        }
    }

    @Test
    void withoutLimitASmallUnsolvableProblemIsCompleteAndEmpty() {
        // 4 cursos y 3 grupos por curso: sin solución, pero el árbol es diminuto.
        Problem problem = new Problem(courses(4), pigeonhole(4, 3), 5);
        for (Solver solver : solvers()) {
            SearchResult result = solver.search(problem);
            assertTrue(result.complete());
            assertTrue(result.results().isEmpty());
        }
    }

    @Test
    void withoutLimitASmallSolvableProblemIsComplete() {
        Problem problem = new Problem(courses(3), pigeonhole(3, 4), 5);
        for (Solver solver : solvers()) {
            SearchResult result = solver.search(problem);
            assertTrue(result.complete());
            assertEquals(5, result.results().size());
        }
    }

    @Test
    void aZeroLimitCutsImmediately() {
        Problem problem = new Problem(courses(3), pigeonhole(3, 4), 5, SectionCost.ZERO, Duration.ZERO);
        for (Solver solver : solvers()) {
            SearchResult result = solver.search(problem);
            assertFalse(result.complete());
            assertTrue(result.results().isEmpty());
        }
    }

    @Test
    void aCutWithCostStillReturnsOnlyValidCombinations() {
        // 10 cursos x 10 grupos tiene 10! soluciones; con costo hay que recorrer mucho para el top-K exacto.
        SectionCost cost = section -> Math.floorMod(section.id().value().hashCode(), 7);
        Problem problem = new Problem(courses(10), pigeonhole(10, 10), 5, cost, Duration.ofMillis(20));
        for (Solver solver : solvers()) {
            SearchResult result = solver.search(problem);
            assertTrue(result.results().size() <= 5);
            for (Scored scored : result.results()) {
                assertValid(scored.schedule(), 10);
            }
        }
    }

    @Test
    void aSearchThatEndsBeforeTheLimitIsCompleteEvenWithALimit() {
        Problem problem = new Problem(courses(3), pigeonhole(3, 4), 5, SectionCost.ZERO, Duration.ofSeconds(30));
        for (Solver solver : solvers()) {
            SearchResult result = solver.search(problem);
            assertTrue(result.complete());
            assertEquals(5, result.results().size());
        }
    }

    @Test
    void aNegativeLimitIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> new Problem(courses(1), List.of(), 1, SectionCost.ZERO, Duration.ofMillis(-1)));
    }

    private static void assertValid(Schedule schedule, int expectedCourses) {
        List<Section> sections = schedule.sections();
        assertEquals(expectedCourses, sections.size());
        for (int i = 0; i < sections.size(); i++) {
            for (int j = i + 1; j < sections.size(); j++) {
                assertFalse(sections.get(i).clashesWith(sections.get(j)));
            }
        }
    }
}
