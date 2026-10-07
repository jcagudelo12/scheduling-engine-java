package sched.simulation;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import sched.domain.CourseId;
import sched.domain.Section;
import sched.domain.SectionId;
import sched.domain.SectionSeats;
import sched.domain.StudentContext;
import sched.domain.StudentId;
import sched.domain.TimeSlot;
import sched.domain.Weekday;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Escenario de prueba en JSON: catálogo con cupos y estudiantes.
 *
 * <pre>{@code
 * {
 *   "max_results": 200, "catalog_seq": 1,
 *   "students": [{ "id": "est-001", "courses": ["MAT101"] }],
 *   "sections": [{ "id": "MAT101-01", "course": "MAT101", "capacity": 35, "available": 10,
 *                  "slots": [{ "day": "monday", "start": "06:00", "end": "08:00" }] }]
 * }
 * }</pre>
 *
 * <p>{@code available} es opcional: si falta, el grupo arranca con todo su cupo.
 */
public record Scenario(int maxResults, long catalogSeq, List<SectionSeats> sections, List<StudentContext> students) {

    public static Scenario load(Path path) {
        JsonNode root = JsonMapper.builder().build().readTree(path);

        List<StudentContext> students = new ArrayList<>();
        for (JsonNode student : root.path("students")) {
            List<CourseId> courses = new ArrayList<>();
            for (JsonNode course : student.path("courses")) {
                courses.add(new CourseId(course.asString()));
            }
            students.add(new StudentContext(new StudentId(student.get("id").asString()), courses));
        }

        List<SectionSeats> sections = new ArrayList<>();
        for (JsonNode node : root.path("sections")) {
            String id = node.get("id").asString();
            List<TimeSlot> slots = new ArrayList<>();
            for (JsonNode slot : node.path("slots")) {
                slots.add(parseSlot(slot, id));
            }
            int capacity = node.get("capacity").asInt();
            Section section = new Section(new SectionId(id), new CourseId(node.get("course").asString()), slots, capacity);
            sections.add(new SectionSeats(section, node.has("available") ? node.get("available").asInt() : capacity));
        }

        int maxResults = root.has("max_results") ? root.get("max_results").asInt() : 200;
        long catalogSeq = root.has("catalog_seq") ? root.get("catalog_seq").asLong() : 0;
        return new Scenario(maxResults, catalogSeq, sections, students);
    }

    private static TimeSlot parseSlot(JsonNode slot, String sectionId) {
        Weekday day = Weekday.valueOf(slot.get("day").asString().toUpperCase(Locale.ROOT));
        return TimeSlot.of(day, minutes(slot.get("start").asString()), minutes(slot.get("end").asString()))
                .orElseThrow(() -> new IllegalArgumentException("franja inválida en " + sectionId));
    }

    /** "07:30" → 450. */
    private static int minutes(String hhmm) {
        String[] parts = hhmm.split(":");
        if (parts.length != 2) {
            throw new IllegalArgumentException("hora inválida: " + hhmm);
        }
        int hours = Integer.parseInt(parts[0]);
        int minutes = Integer.parseInt(parts[1]);
        if (hours > 23 || minutes > 59) {
            throw new IllegalArgumentException("hora inválida: " + hhmm);
        }
        return hours * 60 + minutes;
    }
}
