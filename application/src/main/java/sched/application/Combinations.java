package sched.application;

import java.util.List;
import sched.domain.Schedule;

/**
 * Resultado junto con el estado del catálogo con que se calculó.
 *
 * @param catalogSeq {@code seq} del catálogo usado, definido por la institución
 * @param stale {@code true} si la réplica podía no estar al día (ADR 0003)
 * @param totalCredits suma de créditos de los cursos pedidos (los de crédito desconocido cuentan 0)
 * @param maxCredits tope de créditos del periodo; {@code 0} si no hay tope
 */
public record Combinations(
        long catalogSeq, boolean stale, int totalCredits, int maxCredits, List<Schedule> schedules) {
}
