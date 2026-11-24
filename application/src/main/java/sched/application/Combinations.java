package sched.application;

import java.util.List;

/**
 * Resultado junto con el estado del catálogo con que se calculó.
 *
 * @param catalogSeq {@code seq} del catálogo usado, definido por la institución
 * @param stale {@code true} si la réplica podía no estar al día (ADR 0003)
 * @param totalCredits suma de créditos de los cursos pedidos (los de crédito desconocido cuentan 0)
 * @param maxCredits tope de créditos del periodo; {@code 0} si no hay tope
 * @param schedules combinaciones de menor a mayor penalización (empates por ids de grupo)
 * @param complete {@code false} si la búsqueda se cortó por el tiempo máximo: las combinaciones
 *     son válidas pero no está garantizado que sean las mejores (ni todas las pedidas)
 */
public record Combinations(
        long catalogSeq, boolean stale, int totalCredits, int maxCredits, List<ScoredSchedule> schedules, boolean complete) {
}
