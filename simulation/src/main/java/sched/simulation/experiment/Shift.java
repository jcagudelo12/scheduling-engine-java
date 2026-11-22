package sched.simulation.experiment;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import sched.domain.Preferences;
import sched.domain.Section;
import sched.domain.TimeSlot;
import sched.domain.Weekday;

/**
 * Jornada del día. La oferta ocupa bloques de 2 h entre las 6:00 y las 22:00, de lunes a sábado.
 */
public enum Shift {
    MANANA(6, 12, new int[] {6, 8, 10}),
    TARDE(12, 18, new int[] {12, 14, 16}),
    NOCHE(18, 22, new int[] {18, 20});

    /** Primer y último minuto del día académico. */
    static final int DAY_START = 6 * 60;
    static final int DAY_END = 22 * 60;
    static final List<Weekday> DAYS = List.of(Weekday.MONDAY, Weekday.TUESDAY, Weekday.WEDNESDAY,
            Weekday.THURSDAY, Weekday.FRIDAY, Weekday.SATURDAY);
    /** Horas de inicio de los bloques de 2 h. */
    static final int[] BLOCK_STARTS = {6, 8, 10, 12, 14, 16, 18, 20};

    private final int startMinute;
    private final int endMinute;
    private final int[] blockStarts;

    Shift(int startHour, int endHour, int[] blockStarts) {
        this.startMinute = startHour * 60;
        this.endMinute = endHour * 60;
        this.blockStarts = blockStarts;
    }

    public String label() {
        return name().toLowerCase(Locale.ROOT);
    }

    int[] blockStarts() {
        return blockStarts;
    }

    /** Fracción de las franjas del grupo que empiezan dentro de esta jornada (0 si no tiene franjas). */
    double fractionIn(Section section) {
        if (section.slots().isEmpty()) {
            return 0;
        }
        long inside = section.slots().stream()
                .filter(s -> s.start() >= startMinute && s.start() < endMinute)
                .count();
        return (double) inside / section.slots().size();
    }

    /**
     * Ventanas que el estudiante prefiere evitar (restricciones suaves del motor): todo el día
     * académico fuera de esta jornada, de lunes a sábado.
     */
    Preferences avoiding() {
        List<TimeSlot> avoid = new ArrayList<>();
        for (Weekday day : DAYS) {
            if (startMinute > DAY_START) {
                TimeSlot.of(day, DAY_START, startMinute).ifPresent(avoid::add);
            }
            if (endMinute < DAY_END) {
                TimeSlot.of(day, endMinute, DAY_END).ifPresent(avoid::add);
            }
        }
        return new Preferences(avoid);
    }
}
