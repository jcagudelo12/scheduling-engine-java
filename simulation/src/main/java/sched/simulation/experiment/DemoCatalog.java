package sched.simulation.experiment;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import sched.domain.Course;
import sched.domain.SectionSeats;
import sched.domain.TimeSlot;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * Catálogo de demostración para el cliente web: una oferta sintética del {@link OfferGenerator}
 * (supuestos de {@code docs/simulador.md}) exportada en el formato de {@code Scenario}, con
 * créditos y tope de créditos, para cargarla con {@code load}. Incluye unos pocos estudiantes
 * (los primeros perfiles sintéticos) para probar el cliente sin token.
 */
public final class DemoCatalog {

    /** Perfiles con que se genera la oferta: da una oferta del tamaño de un periodo real, con cupos ajustados. */
    static final int PROFILES = 300;
    /** Estudiantes de ejemplo que se incluyen en el escenario. */
    static final int STUDENTS = 8;

    private DemoCatalog() {
    }

    /** Escribe el escenario con la oferta de la semilla dada; devuelve un resumen para mostrar. */
    public static String write(Path out, long seed) throws IOException {
        ExperimentConfig config = ExperimentConfig.defaults().withSeed(seed).withProfiles(PROFILES);
        Experiment.Setup setup = Experiment.prepare(config);
        Offer offer = setup.offer();

        Map<String, Object> root = new LinkedHashMap<>();
        root.put("max_results", 200);
        root.put("catalog_seq", 1);
        root.put("max_credits", offer.rules().maxCredits());

        List<Map<String, Object>> courses = new ArrayList<>();
        for (Course course : offer.courses()) {
            courses.add(Map.of("id", course.id().value(), "credits", course.credits()));
        }
        root.put("courses", courses);

        List<Map<String, Object>> students = new ArrayList<>();
        for (Profile profile : setup.profiles().stream().limit(STUDENTS).toList()) {
            Map<String, Object> student = new LinkedHashMap<>();
            student.put("id", profile.id());
            student.put("courses", profile.courses().stream().map(c -> c.value()).toList());
            students.add(student);
        }
        root.put("students", students);

        List<Map<String, Object>> sections = new ArrayList<>();
        for (SectionSeats seats : offer.sections()) {
            Map<String, Object> section = new LinkedHashMap<>();
            section.put("id", seats.section().id().value());
            section.put("course", seats.section().course().value());
            section.put("capacity", seats.section().capacity());
            section.put("available", seats.available());
            List<Map<String, String>> slots = new ArrayList<>();
            for (TimeSlot slot : seats.section().slots()) {
                slots.add(Map.of("day", slot.day().name().toLowerCase(java.util.Locale.ROOT),
                        "start", hhmm(slot.start()), "end", hhmm(slot.end())));
            }
            section.put("slots", slots);
            sections.add(section);
        }
        root.put("sections", sections);

        Path parent = out.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        JsonMapper.builder().enable(SerializationFeature.INDENT_OUTPUT).build().writeValue(out, root);
        String summary = String.format(
                "catálogo de demostración: semilla=%d asignaturas=%d grupos=%d estudiantes=%d max_credits=%d -> %s",
                seed, courses.size(), sections.size(), students.size(), offer.rules().maxCredits(), out);
        if (!setup.profiles().isEmpty()) {
            Profile first = setup.profiles().getFirst();
            summary += String.format("%nejemplo para el cliente web: estudiante %s, asignaturas %s", first.id(),
                    String.join(", ", first.courses().stream().map(c -> c.value()).toList()));
        }
        return summary;
    }

    /** 450 → "07:30". */
    static String hhmm(int minutes) {
        return String.format("%02d:%02d", minutes / 60, minutes % 60);
    }
}
