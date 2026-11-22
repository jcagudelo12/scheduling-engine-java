package sched.solver;

import java.util.List;
import sched.domain.Schedule;

/** Estrategia para generar combinaciones de horario. Síncrona y pura. */
public interface Solver {

    /**
     * Hasta {@code maxResults} combinaciones con su costo total. Con costo, son las de menor
     * costo total; sin costo, cualesquiera que cumplan.
     */
    List<Scored> solveScored(Problem problem);

    default List<Schedule> solve(Problem problem) {
        return solveScored(problem).stream().map(Scored::schedule).toList();
    }
}
