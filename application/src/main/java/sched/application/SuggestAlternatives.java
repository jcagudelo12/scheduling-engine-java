package sched.application;

import static sched.application.Alternatives.Alternative;
import static sched.application.Alternatives.Change;
import static sched.application.Alternatives.Choice;
import static sched.application.Alternatives.Clash;
import static sched.application.Alternatives.Conflicts;
import static sched.application.Alternatives.Reason;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import sched.domain.CourseId;
import sched.domain.EnrollmentRules;
import sched.domain.Preferences;
import sched.domain.Section;
import sched.domain.SectionId;
import sched.domain.StudentContext;
import sched.solver.Problem;
import sched.solver.Scored;
import sched.solver.SearchResult;
import sched.solver.SectionCost;
import sched.solver.Solver;

/**
 * Caso de uso: la selección actual del estudiante dejó de servir (un grupo se quedó sin cupo o
 * hay un cruce) y se proponen combinaciones que conservan la mayor cantidad posible de sus
 * elecciones, filtradas por cupo.
 *
 * <p><b>Distancia.</b> Si {@code s} es la selección y {@code a} una alternativa, la distancia es
 * d(a, s) = |{ i : aᵢ ≠ sᵢ }|, contando solo los cursos con elección. Un curso sin elección
 * aporta distancia 0 con cualquier grupo (se elige libremente) y aparece en {@code changes} con
 * la razón {@link Reason#NOT_SELECTED}. Una elección para un curso que el estudiante no pidió, o
 * un grupo que existe pero es de otro curso, es una {@link AppException.InvalidSelection}.
 *
 * <p><b>Orden lexicográfico.</b> Primero menor distancia y, a igual distancia, menor penalización
 * p(a) (ver {@link Preferences}); no es una suma ponderada: conservar los grupos elegidos tiene
 * prioridad absoluta sobre las preferencias. Para que el solver lo resuelva con su costo aditivo,
 * se codifica en un solo {@code long}: cost(g) = (g ≠ s[curso(g)] ? W : 0) + penalización(g), con
 * W = 1 + Σ cursos max penalización de sus candidatos. Como la penalización total de cualquier
 * combinación es a lo sumo W - 1, una unidad más de distancia (W) siempre pesa más que cualquier
 * diferencia de penalización. W se calcula con aritmética exacta; las penalizaciones son conteos
 * de franjas (decenas como mucho), así que el costo total, a lo sumo n·(W + máxima penalización),
 * queda muy lejos de desbordar un {@code long}. La distancia y la penalización que se devuelven
 * se recalculan de la combinación, no se decodifican del costo.
 *
 * <p><b>Razones del cambio</b> (por curso con elección cuyo grupo cambia): {@code NO_SEATS} si el
 * grupo elegido no tiene cupo o no existe; {@code CLASH} si tiene cupo pero se cruza con otro
 * grupo con cupo de la selección; {@code REARRANGED} si era válido y cambia para acomodar otros.
 * Los cruces con un grupo sin cupo no se reportan: ese grupo se va a cambiar de todos modos.
 *
 * <p><b>Grupos ya inscritos.</b> El estudiante puede indicar cuáles de sus elecciones ya tiene
 * inscritas ({@code enrolled}); esos grupos cuentan como con cupo aunque la réplica marque 0,
 * porque el cupo que falta es el suyo.
 *
 * <p>Verifica el tope de créditos igual que {@link GenerateCombinations}. Devuelve a lo sumo
 * {@code maxAlternatives} alternativas (parámetro propio, {@code SCHED_MAX_ALTERNATIVES}).
 *
 * <p>La búsqueda tiene un tiempo máximo opcional ({@code SCHED_SEARCH_TIMEOUT_MS}). Si vence, se
 * responde con lo encontrado y {@link Alternatives#complete()} en {@code false}.
 */
public final class SuggestAlternatives {

    /** Lo que se lee del catálogo de un grupo elegido; {@code section} es null si no existe. */
    private record Selected(CourseId course, SectionId id, Section section, boolean hasSeats) {
    }

    private record Snapshot(
            long seq,
            boolean stale,
            int totalCredits,
            int maxCredits,
            List<Section> candidates,
            Map<CourseId, Selected> selected) {
    }

    private final Solver solver;
    private final CatalogReplica replica;
    private final int maxAlternatives;
    private final Duration searchTimeout;

    /** Sin tiempo máximo de búsqueda. */
    public SuggestAlternatives(Solver solver, CatalogReplica replica, int maxAlternatives) {
        this(solver, replica, maxAlternatives, null);
    }

    /** @param searchTimeout tiempo máximo de la búsqueda; {@code null} = sin límite */
    public SuggestAlternatives(Solver solver, CatalogReplica replica, int maxAlternatives, Duration searchTimeout) {
        this.solver = solver;
        this.replica = replica;
        this.maxAlternatives = maxAlternatives;
        this.searchTimeout = searchTimeout;
    }

    /** Sin grupos ya inscritos: todo grupo de la selección sin cupo en la réplica es {@code NO_SEATS}. */
    public Alternatives execute(StudentContext student, Map<CourseId, SectionId> selection) throws AppException {
        return execute(student, selection, Set.of());
    }

    /**
     * @param enrolled grupos de la selección que el estudiante ya tiene inscritos. Ya tienen su
     *     cupo, así que cuentan como disponibles aunque la réplica marque 0 (quizá el último cupo
     *     lo tomó él): se agregan a los candidatos y no generan {@code NO_SEATS}. Cada uno debe
     *     estar en {@code selection}, o es {@link AppException.InvalidSelection}.
     */
    public Alternatives execute(StudentContext student, Map<CourseId, SectionId> selection, Set<SectionId> enrolled)
            throws AppException {
        for (SectionId id : enrolled) {
            if (!selection.containsValue(id)) {
                throw new AppException.InvalidSelection(
                        "el grupo inscrito " + id + " no está en la selección");
            }
        }
        Set<CourseId> eligible = new HashSet<>(student.eligibleCourses());
        for (CourseId course : selection.keySet()) {
            if (!eligible.contains(course)) {
                throw new AppException.InvalidSelection(
                        "la selección incluye el curso " + course + ", que no está entre los pedidos");
            }
        }

        Snapshot snapshot = replica.read((catalog, stale) -> {
            int total = catalog.totalCredits(student.eligibleCourses());
            EnrollmentRules rules = catalog.rules();
            boolean exceeds = CreditCheck.exceeds(total, rules.maxCredits());
            List<Section> candidates = new ArrayList<>(
                    exceeds ? List.of() : catalog.openSectionsOf(student.eligibleCourses()));
            boolean ownAdded = false;
            Map<CourseId, Selected> selected = new LinkedHashMap<>();
            if (!exceeds) {
                for (CourseId course : student.eligibleCourses()) {
                    SectionId id = selection.get(course);
                    if (id != null) {
                        Section section = catalog.sectionOf(id).orElse(null);
                        boolean own = section != null && section.course().equals(course) && enrolled.contains(id);
                        boolean seats = own || catalog.available(id).orElse(0) > 0;
                        if (own && candidates.stream().noneMatch(c -> c.id().equals(id))) {
                            candidates.add(section);
                            ownAdded = true;
                        }
                        selected.put(course, new Selected(course, id, section, seats));
                    }
                }
            }
            if (ownAdded) {
                // Mismo orden que openSectionsOf: por curso (como se pidieron) y luego por id.
                List<CourseId> order = student.eligibleCourses();
                candidates.sort(Comparator.<Section>comparingInt(c -> order.indexOf(c.course()))
                        .thenComparing(Section::id));
            }
            return new Snapshot(catalog.seq(), stale, total, rules.maxCredits(), candidates, selected);
        });

        CreditCheck.verify(snapshot.totalCredits(), snapshot.maxCredits());

        for (Selected s : snapshot.selected().values()) {
            if (s.section() != null && !s.section().course().equals(s.course())) {
                throw new AppException.InvalidSelection(
                        "el grupo " + s.id() + " es del curso " + s.section().course() + ", no de " + s.course());
            }
        }

        Conflicts conflicts = conflictsOf(student.eligibleCourses(), snapshot.selected());
        Map<CourseId, Reason> reasons = reasonsOf(conflicts);
        boolean selectionValid = snapshot.selected().size() == student.eligibleCourses().size()
                && reasons.isEmpty();

        Map<CourseId, SectionId> chosen = new HashMap<>();
        snapshot.selected().forEach((course, s) -> chosen.put(course, s.id()));
        Preferences preferences = student.preferences();
        SectionCost cost = lexicographicCost(snapshot.candidates(), chosen, preferences);
        Problem problem = new Problem(student.eligibleCourses(), snapshot.candidates(), maxAlternatives, cost, searchTimeout);

        SearchResult found = solver.search(problem);
        List<Alternative> alternatives = new ArrayList<>();
        for (Scored scored : found.results()) {
            alternatives.add(describe(scored, student.eligibleCourses(), chosen, reasons, preferences));
        }
        return new Alternatives(snapshot.seq(), snapshot.stale(), snapshot.totalCredits(), snapshot.maxCredits(),
                selectionValid, conflicts, alternatives, found.complete());
    }

    /** cost(g) = (g ≠ elección del curso ? W : 0) + penalización(g); ver la documentación de la clase. */
    private static SectionCost lexicographicCost(
            List<Section> candidates, Map<CourseId, SectionId> chosen, Preferences preferences) {
        Map<CourseId, Long> maxPenalty = new HashMap<>();
        for (Section section : candidates) {
            maxPenalty.merge(section.course(), preferences.penaltyOf(section), Math::max);
        }
        long w = 1;
        for (long max : maxPenalty.values()) {
            w = Math.addExact(w, max);
        }
        long weight = w;
        return section -> {
            long penalty = preferences.penaltyOf(section);
            SectionId own = chosen.get(section.course());
            return own != null && !own.equals(section.id()) ? Math.addExact(weight, penalty) : penalty;
        };
    }

    private static Conflicts conflictsOf(List<CourseId> courses, Map<CourseId, Selected> selected) {
        List<Choice> withoutSeats = new ArrayList<>();
        List<Selected> seated = new ArrayList<>();
        for (CourseId course : courses) {
            Selected s = selected.get(course);
            if (s == null) {
                continue;
            }
            if (s.section() == null || !s.hasSeats()) {
                withoutSeats.add(new Choice(course, s.id()));
            } else {
                seated.add(s);
            }
        }
        List<Clash> clashes = new ArrayList<>();
        for (int i = 0; i < seated.size(); i++) {
            for (int j = i + 1; j < seated.size(); j++) {
                Selected a = seated.get(i);
                Selected b = seated.get(j);
                if (a.section().clashesWith(b.section())) {
                    clashes.add(new Clash(new Choice(a.course(), a.id()), new Choice(b.course(), b.id())));
                }
            }
        }
        return new Conflicts(withoutSeats, clashes);
    }

    /** Razón por la que el grupo elegido de cada curso con problema debe cambiar. */
    private static Map<CourseId, Reason> reasonsOf(Conflicts conflicts) {
        Map<CourseId, Reason> reasons = new HashMap<>();
        conflicts.withoutSeats().forEach(c -> reasons.put(c.course(), Reason.NO_SEATS));
        for (Clash clash : conflicts.clashes()) {
            reasons.putIfAbsent(clash.first().course(), Reason.CLASH);
            reasons.putIfAbsent(clash.second().course(), Reason.CLASH);
        }
        return reasons;
    }

    private static Alternative describe(
            Scored scored,
            List<CourseId> courses,
            Map<CourseId, SectionId> chosen,
            Map<CourseId, Reason> reasons,
            Preferences preferences) {
        Map<CourseId, Section> byCourse = new HashMap<>();
        long penalty = 0;
        for (Section section : scored.schedule().sections()) {
            byCourse.put(section.course(), section);
            penalty += preferences.penaltyOf(section);
        }
        List<Change> changes = new ArrayList<>();
        int distance = 0;
        for (CourseId course : courses) {
            Section to = byCourse.get(course);
            SectionId from = chosen.get(course);
            if (from == null) {
                changes.add(new Change(course, null, to.id(), Reason.NOT_SELECTED));
            } else if (!from.equals(to.id())) {
                distance++;
                changes.add(new Change(course, from, to.id(), reasons.getOrDefault(course, Reason.REARRANGED)));
            }
        }
        return new Alternative(scored.schedule(), distance, penalty, changes);
    }
}
