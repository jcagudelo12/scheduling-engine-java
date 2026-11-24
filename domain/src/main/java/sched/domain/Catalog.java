package sched.domain;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.TreeSet;

/**
 * Réplica del catálogo que envía la institución (ADR 0003): todos los grupos del periodo
 * con sus cupos disponibles, los créditos de cada asignatura y las reglas de matrícula.
 *
 * <p>Créditos y reglas llegan solo en la carga completa; los cambios incrementales (cupos,
 * grupos) no los modifican.
 *
 * <p>No es segura para hilos; {@code CatalogReplica} controla el acceso concurrente.
 */
public final class Catalog {

    private record Entry(Section section, int available) {
    }

    private long seq;
    private final Map<SectionId, Entry> entries = new HashMap<>();
    private final Map<CourseId, Integer> credits = new HashMap<>();
    private EnrollmentRules rules = EnrollmentRules.NONE;
    /**
     * Índice curso → grupos, para no recorrer todo el catálogo en cada consulta.
     * Los grupos van ordenados por id para que los resultados sean reproducibles.
     */
    private final Map<CourseId, TreeSet<SectionId>> byCourse = new HashMap<>();

    private Catalog(long seq) {
        this.seq = seq;
    }

    /** Construye el catálogo a partir de una carga completa, sin créditos ni reglas. */
    public static Catalog load(long seq, Iterable<SectionSeats> sections) {
        return load(seq, sections, List.of(), EnrollmentRules.NONE);
    }

    /** Construye el catálogo a partir de una carga completa. */
    public static Catalog load(
            long seq, Iterable<SectionSeats> sections, Iterable<Course> courses, EnrollmentRules rules) {
        Catalog catalog = new Catalog(seq);
        for (SectionSeats s : sections) {
            catalog.insert(s.section(), s.available());
        }
        for (Course course : courses) {
            catalog.credits.put(course.id(), course.credits());
        }
        catalog.rules = rules;
        return catalog;
    }

    public EnrollmentRules rules() {
        return rules;
    }

    /** Créditos de la asignatura; {@code 0} si la institución no los informó. */
    public int creditsOf(CourseId course) {
        return credits.getOrDefault(course, 0);
    }

    /** Suma de créditos de las asignaturas indicadas; las repetidas cuentan una sola vez. */
    public int totalCredits(Iterable<CourseId> courses) {
        int total = 0;
        Set<CourseId> seen = new HashSet<>();
        for (CourseId course : courses) {
            if (seen.add(course)) {
                total += creditsOf(course);
            }
        }
        return total;
    }

    public long seq() {
        return seq;
    }

    public int size() {
        return entries.size();
    }

    public OptionalInt available(SectionId section) {
        Entry entry = entries.get(section);
        return entry == null ? OptionalInt.empty() : OptionalInt.of(entry.available());
    }

    /** Asignaturas que tienen al menos un grupo en el catálogo, ordenadas por id (lectura; no modifica nada). */
    public List<CourseId> courseIds() {
        return byCourse.keySet().stream().sorted().toList();
    }

    /** El grupo con ese id, tenga o no cupo; vacío si no está en el catálogo. */
    public Optional<Section> sectionOf(SectionId id) {
        Entry entry = entries.get(id);
        return entry == null ? Optional.empty() : Optional.of(entry.section());
    }

    /**
     * Grupos de los cursos indicados que aún tienen cupo, curso por curso.
     *
     * <p>El costo depende de cuántos grupos tienen esos cursos, no del tamaño del catálogo.
     */
    public List<Section> openSectionsOf(List<CourseId> courses) {
        List<Section> open = new ArrayList<>();
        Set<CourseId> seen = new HashSet<>();
        for (CourseId course : courses) {
            // Un curso repetido en la lista no debe repetir sus grupos.
            if (!seen.add(course)) {
                continue;
            }
            Set<SectionId> ids = byCourse.get(course);
            if (ids == null) {
                continue;
            }
            for (SectionId id : ids) {
                Entry entry = entries.get(id);
                if (entry != null && entry.available() > 0) {
                    open.add(entry.section());
                }
            }
        }
        return open;
    }

    /** Aplica un cambio respetando el orden estricto de {@code seq}. */
    public ApplyOutcome apply(CatalogEvent event) throws SequenceGapException {
        long expected = seq + 1;
        if (event.seq() < expected) {
            return ApplyOutcome.DUPLICATE;
        }
        if (event.seq() > expected) {
            throw new SequenceGapException(expected, event.seq());
        }

        switch (event.change()) {
            case CatalogChange.SeatsChanged(SectionId id, int available) -> {
                // Un cambio de cupo sobre un grupo desconocido no tiene datos para crearlo.
                Entry entry = entries.get(id);
                if (entry != null) {
                    entries.put(id, new Entry(entry.section(), available));
                }
            }
            case CatalogChange.SectionUpserted(Section section, int available) -> insert(section, available);
            case CatalogChange.SectionRemoved(SectionId id) -> remove(id);
        }
        seq = event.seq();
        return ApplyOutcome.APPLIED;
    }

    /** Inserta o reemplaza un grupo manteniendo el índice por curso al día. */
    private void insert(Section section, int available) {
        Entry previous = entries.put(section.id(), new Entry(section, available));
        // Si el grupo cambió de curso, sale del índice del curso anterior.
        if (previous != null && !previous.section().course().equals(section.course())) {
            unindex(previous.section().course(), section.id());
        }
        byCourse.computeIfAbsent(section.course(), c -> new TreeSet<>()).add(section.id());
    }

    private void remove(SectionId id) {
        Entry entry = entries.remove(id);
        if (entry != null) {
            unindex(entry.section().course(), id);
        }
    }

    private void unindex(CourseId course, SectionId id) {
        TreeSet<SectionId> ids = byCourse.get(course);
        if (ids != null) {
            ids.remove(id);
            if (ids.isEmpty()) {
                byCourse.remove(course);
            }
        }
    }

    /** Solo para pruebas: cursos presentes en el índice. */
    int indexedCourses() {
        return byCourse.size();
    }
}
