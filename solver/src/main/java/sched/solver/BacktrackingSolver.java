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
 */
public final class BacktrackingSolver implements Solver {

    @Override
    public List<Scored> solveScored(Problem problem) {
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
                return List.of();
            }
            options.add(sections);
        }

        int limit = problem.hasCost() ? Integer.MAX_VALUE : problem.maxResults();
        List<Schedule> found = new ArrayList<>();
        search(options, 0, new ArrayList<>(options.size()), found, limit);

        List<Scored> scored = new ArrayList<>(found.size());
        for (Schedule schedule : found) {
            long total = 0;
            for (Section s : schedule.sections()) {
                total += problem.cost().of(s);
            }
            scored.add(new Scored(schedule, total));
        }
        if (!problem.hasCost()) {
            return scored;
        }
        scored.sort(Comparator.comparingLong(Scored::cost).thenComparing(BacktrackingSolver::compareIds));
        return List.copyOf(scored.subList(0, Math.min(problem.maxResults(), scored.size())));
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
            int maxResults) {
        if (results.size() >= maxResults) {
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
            search(options, depth + 1, current, results, maxResults);
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
