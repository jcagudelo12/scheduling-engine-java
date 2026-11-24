package sched.solver;

import java.util.List;

/**
 * Resultado de una búsqueda: las combinaciones encontradas y si la búsqueda terminó.
 *
 * <p>Con {@code complete = true} los resultados son exactos: sin costo, las {@code maxResults}
 * pedidas (o todas las que existen); con costo, las de menor costo total. Con
 * {@code complete = false} el tiempo máximo ({@link Problem#timeLimit()}) venció antes de
 * terminar: toda combinación devuelta es válida, pero son las mejores encontradas hasta ese
 * momento, no un top-K garantizado (puede haber otras de menor costo, o más de las devueltas).
 *
 * @param results combinaciones por costo creciente y luego por ids de grupo
 * @param complete {@code false} si la búsqueda se cortó por el tiempo máximo
 * @param nodes nodos explorados, solo para medir; negativo si el solver no lo informa
 */
public record SearchResult(List<Scored> results, boolean complete, long nodes) {

    public SearchResult {
        results = List.copyOf(results);
    }

    /** Sin información de los nodos explorados. */
    public SearchResult(List<Scored> results, boolean complete) {
        this(results, complete, -1);
    }
}
