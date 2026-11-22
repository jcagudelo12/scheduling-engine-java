package sched.solver;

import java.util.LinkedHashSet;
import java.util.List;
import sched.domain.CourseId;
import sched.domain.Section;

/**
 * Entrada del solver: cursos a cubrir y grupos candidatos (ya filtrados por cupo).
 *
 * <p>{@code cost} es opcional: con {@link SectionCost#ZERO} (o {@code null}) no hay orden de
 * preferencia y basta con encontrar {@code maxResults} combinaciones.
 */
public record Problem(List<CourseId> courses, List<Section> candidates, int maxResults, SectionCost cost) {

    public Problem {
        // Un curso repetido no puede aportar dos grupos: se conserva la primera aparición.
        courses = List.copyOf(new LinkedHashSet<>(courses));
        candidates = List.copyOf(candidates);
        cost = cost == null ? SectionCost.ZERO : cost;
    }

    /** Problema sin costo. */
    public Problem(List<CourseId> courses, List<Section> candidates, int maxResults) {
        this(courses, candidates, maxResults, SectionCost.ZERO);
    }

    boolean hasCost() {
        return cost != SectionCost.ZERO;
    }
}
