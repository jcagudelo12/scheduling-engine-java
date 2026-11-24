package sched.solver;

import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.List;
import sched.domain.CourseId;
import sched.domain.Section;

/**
 * Entrada del solver: cursos a cubrir y grupos candidatos (ya filtrados por cupo).
 *
 * <p>{@code cost} es opcional: con {@link SectionCost#ZERO} (o {@code null}) no hay orden de
 * preferencia y basta con encontrar {@code maxResults} combinaciones.
 *
 * <p>{@code timeLimit} es opcional ({@code null} = sin límite): tiempo máximo de reloj de la
 * búsqueda, contado desde que el solver empieza. Si vence, el solver corta y devuelve lo
 * encontrado, con {@link SearchResult#complete()} en {@code false}. No puede ser negativo.
 */
public record Problem(
        List<CourseId> courses, List<Section> candidates, int maxResults, SectionCost cost, Duration timeLimit) {

    public Problem {
        // Un curso repetido no puede aportar dos grupos: se conserva la primera aparición.
        courses = List.copyOf(new LinkedHashSet<>(courses));
        candidates = List.copyOf(candidates);
        cost = cost == null ? SectionCost.ZERO : cost;
        if (timeLimit != null && timeLimit.isNegative()) {
            throw new IllegalArgumentException("El tiempo máximo no puede ser negativo: " + timeLimit);
        }
    }

    /** Problema con costo y sin tiempo máximo. */
    public Problem(List<CourseId> courses, List<Section> candidates, int maxResults, SectionCost cost) {
        this(courses, candidates, maxResults, cost, null);
    }

    /** Problema sin costo. */
    public Problem(List<CourseId> courses, List<Section> candidates, int maxResults) {
        this(courses, candidates, maxResults, SectionCost.ZERO);
    }

    boolean hasCost() {
        return cost != SectionCost.ZERO;
    }
}
