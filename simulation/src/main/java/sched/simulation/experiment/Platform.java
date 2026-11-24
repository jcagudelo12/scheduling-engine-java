package sched.simulation.experiment;

import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import sched.domain.CourseId;
import sched.domain.Section;
import sched.domain.SectionId;
import sched.domain.SectionSeats;
import sched.domain.TimeSlot;

/**
 * Plataforma de matrícula simulada: la fuente de verdad de los cupos y el único validador de
 * envíos. Acepta o rechaza cada envío de un grupo y es independiente del motor: revisa los cruces
 * con su propio código (no usa el solver) y es quien cuenta los rechazos.
 *
 * <p>Orden de validación (supuesto): primero el cruce con lo ya inscrito del estudiante y luego el
 * cupo. Al cambiar un cupo avisa al {@link SeatListener}, que en la condición B lo lleva a la
 * réplica del motor.
 */
final class Platform {

    enum Outcome { ACCEPTED, CLASH, FULL }

    @FunctionalInterface
    interface SeatListener {
        void changed(SectionId section, int available);
    }

    private final Map<SectionId, Integer> available = new HashMap<>();
    private final Map<SectionId, Integer> capacity = new HashMap<>();
    private final Map<Integer, Map<CourseId, Section>> enrolled = new HashMap<>();
    private final SeatListener listener;

    Platform(Collection<SectionSeats> sections, SeatListener listener) {
        for (SectionSeats s : sections) {
            available.put(s.section().id(), s.available());
            capacity.put(s.section().id(), s.section().capacity());
        }
        this.listener = listener;
    }

    int available(SectionId id) {
        Integer seats = available.get(id);
        if (seats == null) {
            throw new IllegalArgumentException("grupo desconocido: " + id);
        }
        return seats;
    }

    /** Grupos inscritos del estudiante, por asignatura (en orden de inscripción). */
    Map<CourseId, Section> enrolledOf(int student) {
        return enrolled.computeIfAbsent(student, k -> new LinkedHashMap<>());
    }

    Outcome submit(int student, Section section) {
        Map<CourseId, Section> mine = enrolledOf(student);
        if (mine.containsKey(section.course())) {
            throw new IllegalStateException("el estudiante " + student + " ya tiene la asignatura " + section.course());
        }
        for (Section other : mine.values()) {
            if (clash(section, other)) {
                return Outcome.CLASH;
            }
        }
        int seats = available(section.id());
        if (seats <= 0) {
            return Outcome.FULL;
        }
        available.put(section.id(), seats - 1);
        mine.put(section.course(), section);
        listener.changed(section.id(), seats - 1);
        return Outcome.ACCEPTED;
    }

    /** Retira un grupo inscrito y devuelve el cupo. */
    void withdraw(int student, CourseId course) {
        Section section = enrolledOf(student).remove(course);
        if (section == null) {
            throw new IllegalStateException("el estudiante " + student + " no tiene la asignatura " + course);
        }
        int seats = available(section.id()) + 1;
        available.put(section.id(), seats);
        listener.changed(section.id(), seats);
    }

    /** Validador propio de cruces: dos franjas del mismo día que se solapan. */
    static boolean clash(Section a, Section b) {
        for (TimeSlot x : a.slots()) {
            for (TimeSlot y : b.slots()) {
                if (x.day() == y.day() && x.start() < y.end() && y.start() < x.end()) {
                    return true;
                }
            }
        }
        return false;
    }

    static boolean hasClash(List<Section> sections) {
        for (int i = 0; i < sections.size(); i++) {
            for (int j = i + 1; j < sections.size(); j++) {
                if (clash(sections.get(i), sections.get(j))) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Ningún cupo negativo ni sobre la capacidad, y los cupos tomados cuadran con las inscripciones. */
    void verifyInvariants() {
        Map<SectionId, Integer> taken = new HashMap<>();
        enrolled.values().forEach(m -> m.values().forEach(s -> taken.merge(s.id(), 1, Integer::sum)));
        for (Map.Entry<SectionId, Integer> e : available.entrySet()) {
            int seats = e.getValue();
            int cap = capacity.get(e.getKey());
            if (seats < 0 || seats > cap) {
                throw new IllegalStateException("cupo inválido en " + e.getKey() + ": " + seats + " de " + cap);
            }
            // Solo es exacto si el cupo inicial era la capacidad completa; con oferta real se omite.
            if (seats + taken.getOrDefault(e.getKey(), 0) > cap) {
                throw new IllegalStateException("más inscritos que cupo en " + e.getKey());
            }
        }
    }
}
