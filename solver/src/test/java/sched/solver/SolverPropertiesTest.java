package sched.solver;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import sched.domain.CourseId;
import sched.domain.Schedule;
import sched.domain.Section;
import sched.domain.SectionId;
import sched.domain.TimeSlot;
import sched.domain.Weekday;

/**
 * Propiedades del solver sobre problemas aleatorios pequeños, contra un oráculo de fuerza bruta.
 * Verifica la condición de diseño Rtraslape = 0. Para otra implementación, basta sobrescribir {@link #solver()}.
 */
class SolverPropertiesTest {

    private static final int BIG = 1_000_000;

    protected Solver solver() {
        return new BacktrackingSolver();
    }

    // ---- Generadores ----

    @Provide
    Arbitrary<Problem> problems() {
        return Arbitraries.integers().between(1, 6).flatMap(courseCount -> {
            List<Arbitrary<List<Section>>> perCourse = new ArrayList<>();
            for (int c = 0; c < courseCount; c++) {
                perCourse.add(sectionsOf(c));
            }
            return Combinators.combine(perCourse).as(lists -> {
                List<CourseId> courses = new ArrayList<>();
                List<Section> candidates = new ArrayList<>();
                for (int c = 0; c < lists.size(); c++) {
                    courses.add(new CourseId("C" + c));
                    candidates.addAll(lists.get(c));
                }
                return new Problem(courses, candidates, BIG);
            });
        });
    }

    private static Arbitrary<List<Section>> sectionsOf(int course) {
        return Arbitraries.integers().between(1, 5).flatMap(n -> {
            List<Arbitrary<Section>> sections = new ArrayList<>();
            for (int g = 0; g < n; g++) {
                String id = "C" + course + "-G" + g;
                sections.add(slots().map(s -> new Section(
                        new SectionId(id), new CourseId("C" + course), s, 30)));
            }
            return Combinators.combine(sections).as(l -> l);
        });
    }

    private static Arbitrary<List<TimeSlot>> slots() {
        Arbitrary<TimeSlot> slot = Combinators.combine(
                        Arbitraries.of(Weekday.values()),
                        Arbitraries.integers().between(12, 43), // 6:00 a 21:30, cada 30 min
                        Arbitraries.integers().between(1, 4))   // duración 30 a 120 min
                .as((day, startUnit, len) -> {
                    int start = startUnit * 30;
                    int end = Math.min(start + len * 30, 22 * 60);
                    return TimeSlot.of(day, start, end).orElseThrow();
                });
        return slot.list().ofMinSize(1).ofMaxSize(3);
    }

    // ---- Oráculo ----

    private static boolean slotsClash(Section a, Section b) {
        for (TimeSlot x : a.slots()) {
            for (TimeSlot y : b.slots()) {
                if (x.day() == y.day() && x.start() < y.end() && y.start() < x.end()) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Producto cartesiano filtrado; cada combinación como conjunto de ids de grupo. */
    private static Set<Set<SectionId>> oracle(Problem p) {
        List<List<Section>> options = new ArrayList<>();
        for (CourseId c : p.courses()) {
            options.add(p.candidates().stream().filter(s -> s.course().equals(c)).toList());
        }
        Set<Set<SectionId>> out = new HashSet<>();
        expand(options, 0, new ArrayList<>(), out);
        return out;
    }

    private static void expand(List<List<Section>> options, int depth, List<Section> chosen,
            Set<Set<SectionId>> out) {
        if (depth == options.size()) {
            Set<SectionId> ids = new HashSet<>();
            chosen.forEach(s -> ids.add(s.id()));
            out.add(ids);
            return;
        }
        for (Section s : options.get(depth)) {
            if (chosen.stream().noneMatch(o -> slotsClash(o, s))) {
                chosen.add(s);
                expand(options, depth + 1, chosen, out);
                chosen.removeLast();
            }
        }
    }

    private static Set<SectionId> ids(Schedule s) {
        Set<SectionId> ids = new HashSet<>();
        s.sections().forEach(x -> ids.add(x.id()));
        return ids;
    }

    // ---- Propiedades ----

    @Property
    void noClashes(@ForAll("problems") Problem p) {
        for (Schedule s : solver().solve(p)) {
            assertFalse(s.hasClashes());
            for (int i = 0; i < s.sections().size(); i++) {
                for (int j = i + 1; j < s.sections().size(); j++) {
                    assertFalse(slotsClash(s.sections().get(i), s.sections().get(j)));
                }
            }
        }
    }

    @Property
    void oneSectionPerRequestedCourseFromCandidates(@ForAll("problems") Problem p) {
        for (Schedule s : solver().solve(p)) {
            assertEquals(p.courses().size(), s.sections().size());
            for (int i = 0; i < p.courses().size(); i++) {
                Section chosen = s.sections().get(i);
                assertEquals(p.courses().get(i), chosen.course());
                assertTrue(p.candidates().contains(chosen));
            }
        }
    }

    @Property
    void matchesBruteForceOracle(@ForAll("problems") Problem p) {
        List<Schedule> result = solver().solve(p);
        Set<Set<SectionId>> expected = oracle(p);
        Set<Set<SectionId>> actual = new HashSet<>();
        result.forEach(s -> actual.add(ids(s)));
        assertEquals(expected.size(), result.size());
        assertEquals(expected, actual);
    }

    @Property
    void noDuplicates(@ForAll("problems") Problem p) {
        List<Schedule> result = solver().solve(p);
        assertEquals(result.size(), new HashSet<>(result).size());
    }

    @Property
    void emptyWhenOracleFindsNoSolution(@ForAll("problems") Problem p) {
        if (oracle(p).isEmpty()) {
            assertTrue(solver().solve(p).isEmpty());
        }
    }

    @Property
    void respectsMaxResults(@ForAll("problems") Problem p, @ForAll("limits") int max) {
        Problem limited = new Problem(p.courses(), p.candidates(), max);
        int expected = Math.min(max, oracle(p).size());
        assertEquals(expected, solver().solve(limited).size());
    }

    @Provide
    Arbitrary<Integer> limits() {
        return Arbitraries.integers().between(1, 10);
    }
}
