package sched.solver;

import java.util.List;
import sched.domain.Schedule;

/** Estrategia para generar combinaciones de horario. Síncrona y pura. */
public interface Solver {

    /**
     * Busca hasta {@code maxResults} combinaciones con su costo total. Con costo, son las de menor
     * costo total; sin costo, cualesquiera que cumplan. Si el problema trae
     * {@link Problem#timeLimit()} y vence, devuelve lo encontrado con
     * {@link SearchResult#complete()} en {@code false}.
     */
    SearchResult search(Problem problem);

    /** Como {@link #search(Problem)}, sin saber si la búsqueda terminó. Con límite de tiempo, úselo solo si no importa. */
    default List<Scored> solveScored(Problem problem) {
        return search(problem).results();
    }

    default List<Schedule> solve(Problem problem) {
        return solveScored(problem).stream().map(Scored::schedule).toList();
    }
}
