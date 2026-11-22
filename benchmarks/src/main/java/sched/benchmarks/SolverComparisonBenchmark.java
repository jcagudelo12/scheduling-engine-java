package sched.benchmarks;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;
import sched.domain.CourseId;
import sched.domain.Schedule;
import sched.domain.Section;
import sched.domain.SectionId;
import sched.domain.TimeSlot;
import sched.domain.Weekday;
import sched.solver.BacktrackingSolver;
import sched.solver.ForwardCheckingSolver;
import sched.solver.Problem;
import sched.solver.Solver;

/**
 * Compara los solvers en (a) 6 cursos × 5 grupos con hasta 500 resultados y (b) un peor caso sin
 * solución: 7 cursos × 8 grupos donde el último curso choca con todo.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 5, time = 2)
@Fork(1)
public class SolverComparisonBenchmark {

    private static final Weekday[] DAYS = {
        Weekday.MONDAY, Weekday.TUESDAY, Weekday.WEDNESDAY, Weekday.THURSDAY, Weekday.FRIDAY, Weekday.SATURDAY,
    };

    @Param({"backtracking", "forward-checking"})
    public String solverName;

    private Solver solver;
    private Problem regular;
    private Problem noSolution;

    @Setup
    public void setup() {
        solver = switch (solverName) {
            case "backtracking" -> new BacktrackingSolver();
            case "forward-checking" -> new ForwardCheckingSolver();
            default -> throw new IllegalArgumentException(solverName);
        };
        regular = regularProblem();
        noSolution = noSolutionProblem();
    }

    private static Problem regularProblem() {
        List<Section> candidates = new ArrayList<>();
        List<CourseId> courseIds = new ArrayList<>();
        for (int c = 0; c < 6; c++) {
            courseIds.add(new CourseId("C" + c));
            for (int s = 0; s < 5; s++) {
                Weekday day = DAYS[(c + s) % DAYS.length];
                int start = 360 + ((c * 7 + s * 3) % 7) * 120;
                candidates.add(new Section(
                        new SectionId("C" + c + "-" + s),
                        new CourseId("C" + c),
                        List.of(TimeSlot.of(day, start, start + 120).orElseThrow()),
                        30));
            }
        }
        return new Problem(courseIds, candidates, 500);
    }

    /** Los 6 primeros cursos nunca se cruzan entre sí; los grupos del séptimo ocupan toda la semana. */
    private static Problem noSolutionProblem() {
        List<Section> candidates = new ArrayList<>();
        List<CourseId> courseIds = new ArrayList<>();
        for (int c = 0; c < 7; c++) {
            courseIds.add(new CourseId("C" + c));
            for (int s = 0; s < 8; s++) {
                List<TimeSlot> slots = c < 6
                        ? List.of(TimeSlot.of(Weekday.values()[s % 7], 360 + c * 60, 420 + c * 60).orElseThrow())
                        : Arrays.stream(Weekday.values())
                                .map(d -> TimeSlot.of(d, 360, 1320).orElseThrow())
                                .toList();
                candidates.add(new Section(new SectionId("C" + c + "-" + s), new CourseId("C" + c), slots, 30));
            }
        }
        return new Problem(courseIds, candidates, 500);
    }

    @Benchmark
    public List<Schedule> regular6x5() {
        return solver.solve(regular);
    }

    @Benchmark
    public List<Schedule> noSolution7x8() {
        return solver.solve(noSolution);
    }
}
