package sched.application;

import java.util.List;
import sched.domain.EnrollmentRules;
import sched.domain.Section;
import sched.domain.StudentContext;
import sched.solver.Problem;
import sched.solver.Solver;

/**
 * Caso de uso: el estudiante pide combinaciones de horario para sus cursos habilitados.
 *
 * <p>Solo lee la réplica en memoria y ejecuta el solver. Antes de buscar verifica el tope de
 * créditos del periodo: depende de los cursos y no de los grupos, así que si la suma lo supera
 * no se hace la búsqueda y se lanza {@link AppException.CreditLimitExceeded}. Un curso sin
 * créditos informados cuenta 0, y sin tope ({@code max_credits = 0}) no se verifica nada.
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
            List<Section> candidates = rules.exceedsCreditLimit(total)
                    ? List.of()
                    : catalog.openSectionsOf(student.eligibleCourses());
            return new Snapshot(catalog.seq(), stale, total, rules.maxCredits(), candidates);
        });

        if (new EnrollmentRules(snapshot.maxCredits()).exceedsCreditLimit(snapshot.totalCredits())) {
            throw new AppException.CreditLimitExceeded(snapshot.totalCredits(), snapshot.maxCredits());
        }

        Problem problem = new Problem(student.eligibleCourses(), snapshot.candidates(), maxResults);
        return new Combinations(snapshot.seq(), snapshot.stale(), snapshot.totalCredits(), snapshot.maxCredits(),
                solver.solve(problem));
    }
}
