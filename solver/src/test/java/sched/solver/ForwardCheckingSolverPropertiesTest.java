package sched.solver;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import sched.domain.Schedule;
import sched.domain.Section;

/** Las propiedades generales del solver, más el top-K por costo contra la línea base. */
class ForwardCheckingSolverPropertiesTest extends SolverPropertiesTest {

    private static final int BIG = 1_000_000;

    @Override
    protected Solver solver() {
        return new ForwardCheckingSolver();
    }

    /** Costo pseudoaleatorio pero determinista por grupo; rango corto para provocar empates. */
    private static SectionCost randomCost(int seed) {
        return section -> Math.floorMod(Objects.hash(section.id().value(), seed), 12);
    }

    private static long total(Schedule s, SectionCost cost) {
        long sum = 0;
        for (Section section : s.sections()) {
            sum += cost.of(section);
        }
        return sum;
    }

    @Property
    void topKHasTheSameCostsAsBruteForce(
            @ForAll("problems") Problem p, @ForAll int seed, @ForAll("limits") int max) {
        SectionCost cost = randomCost(seed);
        Problem all = new Problem(p.courses(), p.candidates(), BIG);
        List<Long> expected = new BacktrackingSolver().solve(all).stream()
                .map(s -> total(s, cost))
                .sorted()
                .limit(max)
                .toList();

        List<Scored> actual = new ForwardCheckingSolver()
                .solveScored(new Problem(p.courses(), p.candidates(), max, cost));

        assertEquals(expected, actual.stream().map(Scored::cost).toList());
        Set<Schedule> distinct = new HashSet<>();
        for (Scored s : actual) {
            assertEquals(total(s.schedule(), cost), s.cost());
            assertFalse(s.schedule().hasClashes());
            assertEquals(p.courses().size(), s.schedule().sections().size());
            assertTrue(distinct.add(s.schedule()));
        }
    }

    @Property
    void baselineWithCostAgreesWithForwardChecking(
            @ForAll("problems") Problem p, @ForAll int seed, @ForAll("limits") int max) {
        Problem costed = new Problem(p.courses(), p.candidates(), max, randomCost(seed));
        List<Long> fc = new ForwardCheckingSolver().solveScored(costed).stream().map(Scored::cost).toList();
        List<Long> bt = new BacktrackingSolver().solveScored(costed).stream().map(Scored::cost).toList();
        assertEquals(bt, fc);
    }

    @Property
    void unlimitedSolutionSetMatchesBacktrackingWithCost(@ForAll("problems") Problem p, @ForAll int seed) {
        Problem costed = new Problem(p.courses(), p.candidates(), BIG, randomCost(seed));
        Set<List<String>> fc = idsOf(new ForwardCheckingSolver().solve(costed));
        Set<List<String>> bt = idsOf(new BacktrackingSolver().solve(new Problem(p.courses(), p.candidates(), BIG)));
        assertEquals(bt, fc);
    }

    @Property
    void outputIsOrderedByCostThenIds(@ForAll("problems") Problem p, @ForAll int seed) {
        List<Scored> result = new ForwardCheckingSolver()
                .solveScored(new Problem(p.courses(), p.candidates(), BIG, randomCost(seed)));
        for (int i = 1; i < result.size(); i++) {
            Scored a = result.get(i - 1);
            Scored b = result.get(i);
            assertTrue(a.cost() <= b.cost());
            if (a.cost() == b.cost()) {
                assertTrue(compare(ids(a.schedule()), ids(b.schedule())) < 0);
            }
        }
    }

    private static List<String> ids(Schedule s) {
        return s.sections().stream().map(x -> x.id().value()).toList();
    }

    private static Set<List<String>> idsOf(List<Schedule> schedules) {
        Set<List<String>> out = new HashSet<>();
        schedules.forEach(s -> out.add(ids(s)));
        return out;
    }

    private static int compare(List<String> a, List<String> b) {
        for (int i = 0; i < a.size(); i++) {
            int c = a.get(i).compareTo(b.get(i));
            if (c != 0) {
                return c;
            }
        }
        return 0;
    }
}
