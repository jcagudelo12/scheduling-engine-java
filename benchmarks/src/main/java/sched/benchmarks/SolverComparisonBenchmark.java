package sched.benchmarks;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
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
import sched.solver.SectionCost;
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
    private Problem enrollment5;
    private Problem enrollment50;
    private Problem enrollment500;
    private Problem enrollmentCost5;
    private Problem enrollmentCost50;
    private Problem enrollmentCost500;

    @Setup
    public void setup() {
        solver = switch (solverName) {
            case "backtracking" -> new BacktrackingSolver();
            case "forward-checking" -> new ForwardCheckingSolver();
            default -> throw new IllegalArgumentException(solverName);
        };
        regular = regularProblem();
        noSolution = noSolutionProblem();
        enrollment5 = enrollmentProblem(5, SectionCost.ZERO);
        enrollment50 = enrollmentProblem(50, SectionCost.ZERO);
        enrollment500 = enrollmentProblem(500, SectionCost.ZERO);
        enrollmentCost5 = enrollmentProblem(5, ENROLLMENT_COST);
        enrollmentCost50 = enrollmentProblem(50, ENROLLMENT_COST);
        enrollmentCost500 = enrollmentProblem(500, ENROLLMENT_COST);
    }

    /** Pares de días de las sesiones semanales (lunes/miércoles, martes/jueves, ...). */
    private static final Weekday[][] DAY_PAIRS = {
        {Weekday.MONDAY, Weekday.WEDNESDAY},
        {Weekday.TUESDAY, Weekday.THURSDAY},
        {Weekday.WEDNESDAY, Weekday.FRIDAY},
        {Weekday.MONDAY, Weekday.THURSDAY},
        {Weekday.TUESDAY, Weekday.FRIDAY},
        {Weekday.THURSDAY, Weekday.SATURDAY},
    };

    /** Penaliza franjas que empiezan antes de 08:00 o terminan después de 18:00, más un término por grupo. */
    private static final SectionCost ENROLLMENT_COST = section -> {
        long cost = Math.floorMod(section.id().value().hashCode(), 7);
        for (TimeSlot t : section.slots()) {
            if (t.start() < 8 * 60) {
                cost += 100;
            }
            if (t.end() > 18 * 60) {
                cost += 100;
            }
        }
        return cost;
    };

    /** 7 cursos × 6 grupos, 2 franjas de 120 min por grupo, generados con semilla fija. */
    private static Problem enrollmentProblem(int maxResults, SectionCost cost) {
        Random random = new Random(42);
        List<Section> candidates = new ArrayList<>();
        List<CourseId> courseIds = new ArrayList<>();
        for (int c = 0; c < 7; c++) {
            courseIds.add(new CourseId("M" + c));
            for (int s = 0; s < 6; s++) {
                Weekday[] days = DAY_PAIRS[random.nextInt(DAY_PAIRS.length)];
                int start = 360 + random.nextInt(15) * 60; // inicio entre 06:00 y 20:00, termina a las 22:00 como tarde
                List<TimeSlot> slots = new ArrayList<>();
                for (Weekday d : days) {
                    slots.add(TimeSlot.of(d, start, start + 120).orElseThrow());
                }
                candidates.add(new Section(
                        new SectionId("M" + c + "-" + s), new CourseId("M" + c), slots, 30));
            }
        }
        return new Problem(courseIds, candidates, maxResults, cost);
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

    @Benchmark
    public List<Schedule> enrollment5() {
        return solver.solve(enrollment5);
    }

    @Benchmark
    public List<Schedule> enrollment50() {
        return solver.solve(enrollment50);
    }

    @Benchmark
    public List<Schedule> enrollment500() {
        return solver.solve(enrollment500);
    }

    @Benchmark
    public List<Schedule> enrollmentCost5() {
        return solver.solve(enrollmentCost5);
    }

    @Benchmark
    public List<Schedule> enrollmentCost50() {
        return solver.solve(enrollmentCost50);
    }

    @Benchmark
    public List<Schedule> enrollmentCost500() {
        return solver.solve(enrollmentCost500);
    }
}
