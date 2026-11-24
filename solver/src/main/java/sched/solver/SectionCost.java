package sched.solver;

import sched.domain.Section;

/**
 * Costo de elegir un grupo. Debe ser no negativo; el costo de una combinación es la suma de
 * los costos de sus grupos (aditivo por curso), lo que permite ramificación y poda.
 */
@FunctionalInterface
public interface SectionCost {

    /** Todos los grupos cuestan lo mismo: no hay preferencia entre combinaciones. */
    SectionCost ZERO = section -> 0L;

    long of(Section section);
}
