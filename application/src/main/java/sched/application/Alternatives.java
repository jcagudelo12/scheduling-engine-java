package sched.application;

import java.util.List;
import sched.domain.CourseId;
import sched.domain.Schedule;
import sched.domain.SectionId;

/**
 * Resultado de {@link SuggestAlternatives}, junto con el estado del catálogo con que se calculó.
 *
 * @param catalogSeq {@code seq} del catálogo usado
 * @param stale {@code true} si la réplica podía no estar al día (ADR 0003)
 * @param totalCredits suma de créditos de los cursos pedidos
 * @param maxCredits tope de créditos del periodo; {@code 0} si no hay tope
 * @param selectionValid {@code true} si la selección original cubre todos los cursos, todos sus
 *     grupos tienen cupo y no se cruzan; entonces la primera alternativa es la propia selección
 *     (distancia 0)
 * @param conflicts conflictos detectados en la selección original
 * @param alternatives de menor a mayor distancia y, a igual distancia, de menor a mayor
 *     penalización (empates por ids de grupo)
 * @param complete {@code false} si la búsqueda se cortó por el tiempo máximo: las alternativas
 *     son válidas pero no está garantizado que sean las mejores
 */
public record Alternatives(
        long catalogSeq,
        boolean stale,
        int totalCredits,
        int maxCredits,
        boolean selectionValid,
        Conflicts conflicts,
        List<Alternative> alternatives,
        boolean complete) {

    /** Por qué cambia el grupo de un curso respecto de la selección original. */
    public enum Reason {
        /** El grupo elegido no tiene cupo o ya no existe en el catálogo. */
        NO_SEATS,
        /** El grupo elegido tiene cupo pero se cruza con otro grupo de la selección original. */
        CLASH,
        /** El curso no tenía grupo elegido. */
        NOT_SELECTED,
        /** El grupo elegido era válido; cambia para acomodar el cambio de otros cursos. */
        REARRANGED
    }

    /** Un grupo elegido para un curso. */
    public record Choice(CourseId course, SectionId section) {
    }

    /** Dos grupos de la selección original que se cruzan. */
    public record Clash(Choice first, Choice second) {
    }

    /**
     * Conflictos de la selección original.
     *
     * @param withoutSeats grupos elegidos sin cupo o inexistentes
     * @param clashes pares de grupos elegidos con cupo que se cruzan entre sí
     */
    public record Conflicts(List<Choice> withoutSeats, List<Clash> clashes) {
    }

    /**
     * Cambio de un curso respecto de la selección original.
     *
     * @param from grupo originalmente elegido; {@code null} si el curso no tenía elección
     */
    public record Change(CourseId course, SectionId from, SectionId to, Reason reason) {
    }

    /**
     * Una combinación alternativa.
     *
     * @param distance cursos con elección cuyo grupo difiere de la selección original
     * @param penalty penalización por las preferencias del estudiante
     * @param changes un elemento por curso cuyo grupo difiere de la selección (incluye los cursos
     *     sin elección, que no suman a {@code distance})
     */
    public record Alternative(Schedule schedule, int distance, long penalty, List<Change> changes) {
    }
}
