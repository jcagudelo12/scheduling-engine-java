package sched.solver;

import java.util.LinkedHashSet;
import java.util.List;
import sched.domain.CourseId;
import sched.domain.Section;

/** Entrada del solver: cursos a cubrir y grupos candidatos (ya filtrados por cupo). */
public record Problem(List<CourseId> courses, List<Section> candidates, int maxResults) {

    public Problem {
        // Un curso repetido no puede aportar dos grupos: se conserva la primera aparición.
        courses = List.copyOf(new LinkedHashSet<>(courses));
        candidates = List.copyOf(candidates);
    }
}
