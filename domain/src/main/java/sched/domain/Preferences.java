package sched.domain;

import java.util.List;

/**
 * Preferencias del estudiante: restricciones suaves que ordenan las combinaciones sin descartar
 * ninguna.
 *
 * <p>{@code avoid} es el conjunto E de franjas que prefiere evitar; vacío significa sin
 * preferencias. La penalización de una combinación es p(a) = Σᵢ |F(aᵢ) ∩ E|: el número de franjas
 * elegidas que caen en E.
 *
 * <p>Con franjas {@code [inicio, fin)} en minutos, "una franja del grupo cae en E" se interpreta
 * como que {@link TimeSlot#overlaps se solapa} con alguna ventana de {@code avoid}: tocar la
 * ventana en un solo extremo (clase que termina cuando empieza la ventana) no cuenta, y un
 * solape parcial sí. Cada franja del grupo cuenta una sola vez aunque toque varias ventanas.
 */
public record Preferences(List<TimeSlot> avoid) {

    /** Sin preferencias: todas las combinaciones tienen penalización 0. */
    public static final Preferences NONE = new Preferences(List.of());

    public Preferences {
        avoid = List.copyOf(avoid);
    }

    public boolean isEmpty() {
        return avoid.isEmpty();
    }

    /** Número de franjas del grupo que se solapan con alguna ventana a evitar. */
    public long penaltyOf(Section section) {
        return section.slots().stream()
                .filter(slot -> avoid.stream().anyMatch(slot::overlaps))
                .count();
    }
}
