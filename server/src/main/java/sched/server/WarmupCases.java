package sched.server;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import sched.adapters.http.CombinationsController.SlotDto;
import sched.adapters.http.RequestLimits;
import sched.application.AppException;
import sched.application.CatalogReplica;
import sched.application.Combinations;
import sched.application.GenerateCombinations;
import sched.domain.CourseId;
import sched.domain.Preferences;
import sched.domain.Section;
import sched.domain.StudentContext;
import sched.domain.StudentId;
import sched.domain.TimeSlot;
import sched.domain.Weekday;

/**
 * Entradas sintéticas del calentamiento, generadas de forma determinista (semilla fija) a partir de
 * cursos y grupos reales del catálogo cargado. No hay estudiantes reales: los ids son
 * {@code warmup-N}. Tienen la forma del tráfico del pico (4 a 7 cursos, con y sin ventanas a evitar,
 * y selecciones con grupos inscritos y cruces para las alternativas).
 */
final class WarmupCases {

    /** Mínimo de cursos con cupo para que valga la pena calentar (rango de cursos por solicitud: 4 a 7). */
    static final int MIN_COURSES = 4;
    private static final int MAX_COURSES = 7;

    /**
     * Una solicitud sintética.
     *
     * @param selection curso -> grupo, tomado de una combinación válida con un grupo cambiado
     * @param enrolled grupos de la selección que se dan por inscritos
     */
    record Case(String studentId, List<String> courses, List<SlotDto> avoid, Map<String, String> selection,
                List<String> enrolled) {
    }

    private WarmupCases() {
    }

    /**
     * Genera hasta {@code size} casos. Solo conserva los que tienen al menos una combinación (con la
     * misma búsqueda de producción), para que el calentamiento recorra el solver con resultados
     * y no se agote en rechazos triviales. Vacío si el catálogo no tiene {@link #MIN_COURSES} cursos con cupo.
     */
    static List<Case> generate(
            CatalogReplica replica, GenerateCombinations search, RequestLimits limits, int size, long seed)
            throws AppException.CatalogUnavailable {
        Map<CourseId, List<Section>> open = new LinkedHashMap<>();
        replica.read((catalog, _) -> {
            for (CourseId course : catalog.courseIds()) {
                List<Section> sections = catalog.openSectionsOf(List.of(course));
                if (!sections.isEmpty()) {
                    open.put(course, sections);
                }
            }
            return null;
        });
        List<CourseId> courses = new ArrayList<>(open.keySet());
        if (courses.size() < MIN_COURSES) {
            return List.of();
        }
        int maxCourses = Math.min(Math.min(MAX_COURSES, courses.size()), limits.maxCourses());
        int minCourses = Math.min(MIN_COURSES, maxCourses);
        Random random = new Random(seed);
        List<Case> cases = new ArrayList<>();
        for (int attempt = 0; attempt < size * 8 && cases.size() < size; attempt++) {
            Collections.shuffle(courses, random);
            List<CourseId> picked = new ArrayList<>(
                    courses.subList(0, minCourses + random.nextInt(maxCourses - minCourses + 1)));
            Collections.sort(picked);
            List<SlotDto> avoid = random.nextInt(100) < 35 ? avoid(random, limits.maxAvoid()) : List.of();
            String studentId = "warmup-" + cases.size();
            Combinations found;
            try {
                found = search.execute(new StudentContext(new StudentId(studentId), picked,
                        new Preferences(avoid.stream().map(WarmupCases::toSlot).toList())));
            } catch (AppException _) {
                continue; // p. ej. supera el tope de créditos: no sirve como caso
            }
            if (found.schedules().isEmpty()) {
                continue;
            }
            Map<String, String> selection = new LinkedHashMap<>();
            for (Section section : found.schedules().getFirst().schedule().sections()) {
                selection.put(section.course().value(), section.id().value());
            }
            // Cambia el grupo de un curso por otro cualquiera: genera cruces y grupos sin cupo en la respuesta.
            CourseId changed = picked.get(random.nextInt(picked.size()));
            List<Section> options = open.get(changed);
            selection.put(changed.value(), options.get(random.nextInt(options.size())).id().value());
            List<String> enrolled = new ArrayList<>();
            for (String section : selection.values()) {
                if (random.nextInt(100) < 40) {
                    enrolled.add(section);
                }
            }
            cases.add(new Case(studentId, picked.stream().map(CourseId::value).toList(), avoid, selection,
                    List.copyOf(enrolled)));
        }
        return List.copyOf(cases);
    }

    /** 5 a 11 ventanas de entre 1 y 4 horas en días de semana, como las del tráfico del pico. */
    private static List<SlotDto> avoid(Random random, int maxAvoid) {
        int count = Math.min(maxAvoid, 5 + random.nextInt(7));
        Weekday[] days = Weekday.values();
        Set<String> seen = new HashSet<>();
        List<SlotDto> windows = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            Weekday day = days[random.nextInt(Math.min(5, days.length))];
            int start = 360 + 60 * random.nextInt(12);
            int end = Math.min(1440, start + 60 * (1 + random.nextInt(4)));
            if (seen.add(day + ":" + start + ":" + end)) {
                windows.add(new SlotDto(day.name().toLowerCase(Locale.ROOT), start, end));
            }
        }
        return List.copyOf(windows);
    }

    private static TimeSlot toSlot(SlotDto dto) {
        return TimeSlot.of(Weekday.valueOf(dto.day().toUpperCase(Locale.ROOT)), dto.startMinute(), dto.endMinute())
                .orElseThrow();
    }
}
