package sched.solver;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import sched.domain.CourseId;
import sched.domain.Schedule;
import sched.domain.Section;

/**
 * Enumeración exhaustiva con poda por cruce de horario.
 *
 * <p>Sirve como línea base contra la cual comparar estrategias más elaboradas. Sin costo se
 * detiene en cuanto reúne {@code maxResults}; con costo enumera todas las combinaciones, las
 * ordena por costo total (y luego por ids de grupo en el orden de cursos) y corta. Es
 * deliberadamente simple: no hace ramificación y poda, y sirve de oráculo del top-K.
 *
 * <p>Si {@link Problem#timeLimit()} vence, corta y devuelve lo enumerado hasta ese momento con
 * {@code complete = false}; con costo, entonces, no es un top-K garantizado.
 */
public final class BacktrackingSolver implements Solver {

    @Override
    public SearchResult search(Problem problem) {
        List<List<Section>> options = new ArrayList<>(problem.courses().size());
        for (CourseId course : problem.courses()) {
            List<Section> sections = new ArrayList<>();
            for (Section section : problem.candidates()) {
                if (section.course().equals(course)) {
                    sections.add(section);
                }
            }
            // Un curso sin grupos disponibles hace imposible cualquier combinación completa.
            if (sections.isEmpty()) {
                return new SearchResult(List.of(), true);
            }
            options.add(sections);
        }

        int limit = problem.hasCost() ? Integer.MAX_VALUE : problem.maxResults();
        List<Schedule> found = new ArrayList<>();
        Budget budget = Budget.of(problem);
        search(options, 0, new ArrayList<>(options.size()), found, limit, budget);

        List<Scored> scored = new ArrayList<>(found.size());
        for (Schedule schedule : found) {
            long total = 0;
            for (Section s : schedule.sections()) {
                total += problem.cost().of(s);
            }
            scored.add(new Scored(schedule, total));
        }
        boolean complete = !budget.cut();
        if (!problem.hasCost()) {
            return new SearchResult(scored, complete);
        }
        scored.sort(Comparator.comparingLong(Scored::cost).thenComparing(BacktrackingSolver::compareIds));
        return new SearchResult(scored.subList(0, Math.min(problem.maxResults(), scored.size())), complete);
    }

    private static int compareIds(Scored a, Scored b) {
        List<Section> x = a.schedule().sections();
        List<Section> y = b.schedule().sections();
        for (int i = 0; i < x.size(); i++) {
            int c = x.get(i).id().compareTo(y.get(i).id());
            if (c != 0) {
                return c;
            }
        }
        return 0;
    }

    private static void search(
            List<List<Section>> options,
            int depth,
            List<Section> current,
            List<Schedule> results,
            int maxResults,
            Budget budget) {
        if (results.size() >= maxResults || budget.expired()) {
            return;
        }
        if (depth == options.size()) {
            results.add(new Schedule(current));
            return;
        }
        for (Section section : options.get(depth)) {
            if (clashesWithAny(current, section)) {
                continue;
            }
            current.add(section);
            search(options, depth + 1, current, results, maxResults, budget);
            current.removeLast();
        }
    }

    private static boolean clashesWithAny(List<Section> chosen, Section section) {
        for (Section other : chosen) {
            if (other.clashesWith(section)) {
                return true;
            }
        }
        return false;
    }
}
