package sched.application;

import java.util.List;
import sched.domain.EnrollmentRules;
import sched.domain.Preferences;
import sched.domain.Section;
import sched.domain.StudentContext;
import sched.solver.Problem;
import sched.solver.SectionCost;
import sched.solver.Solver;

/**
 * Caso de uso: el estudiante pide combinaciones de horario para sus cursos habilitados.
 *
 * <p>Solo lee la réplica en memoria y ejecuta el solver. Antes de buscar verifica el tope de
 * créditos del periodo: depende de los cursos y no de los grupos, así que si la suma lo supera
 * no se hace la búsqueda y se lanza {@link AppException.CreditLimitExceeded}. Un curso sin
 * créditos informados cuenta 0, y sin tope ({@code max_credits = 0}) no se verifica nada.
 *
 * <p>Las preferencias del estudiante no descartan combinaciones: las ordenan por penalización
 * (ver {@link Preferences}) y se devuelven las {@code maxResults} de menor penalización.
 */
public final class GenerateCombinations {

    private record Snapshot(
            long seq, boolean stale, int totalCredits, int maxCredits, List<Section> candidates) {
    }

    private final Solver solver;
    private final CatalogReplica replica;
    private final int maxResults;

    public GenerateCombinations(Solver solver, CatalogReplica replica, int maxResults) {
        this.solver = solver;
        this.replica = replica;
        this.maxResults = maxResults;
    }

    public Combinations execute(StudentContext student) throws AppException {
        // Llegan curso por curso y ordenados por id: resultados reproducibles.
        Snapshot snapshot = replica.read((catalog, stale) -> {
            int total = catalog.totalCredits(student.eligibleCourses());
            EnrollmentRules rules = catalog.rules();
            // Si se excede el tope no se leen los grupos: la búsqueda no se va a hacer.
            List<Section> candidates = CreditCheck.exceeds(total, rules.maxCredits())
                    ? List.of()
                    : catalog.openSectionsOf(student.eligibleCourses());
            return new Snapshot(catalog.seq(), stale, total, rules.maxCredits(), candidates);
        });

        CreditCheck.verify(snapshot.totalCredits(), snapshot.maxCredits());

        // Las preferencias son restricciones suaves: solo ordenan. Sin ellas el costo es cero y el
        // solver corta en cuanto reúne maxResults; con ellas devuelve las de menor penalización.
        Preferences preferences = student.preferences();
        SectionCost cost = preferences.isEmpty() ? SectionCost.ZERO : preferences::penaltyOf;
        Problem problem = new Problem(student.eligibleCourses(), snapshot.candidates(), maxResults, cost);
        List<ScoredSchedule> schedules = solver.solveScored(problem).stream()
                .map(scored -> new ScoredSchedule(scored.schedule(), scored.cost()))
                .toList();
        return new Combinations(snapshot.seq(), snapshot.stale(), snapshot.totalCredits(), snapshot.maxCredits(),
                schedules);
    }
}
