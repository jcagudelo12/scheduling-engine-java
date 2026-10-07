package sched.simulation;

import java.util.LinkedHashMap;
import java.util.Map;
import sched.adapters.inmemory.InMemoryCatalogLog;
import sched.application.AppException;
import sched.application.ApplyLogEntry;
import sched.application.CatalogReplica;
import sched.application.GenerateCombinations;
import sched.application.IngestCatalog;
import sched.application.IngestState;
import sched.domain.StudentContext;
import sched.solver.BacktrackingSolver;

/**
 * Corre el motor sobre un escenario, en memoria y sin red.
 *
 * <p>El catálogo entra por el mismo caso de uso que usa el SDK de la institución, con el
 * historial en memoria en lugar de JetStream: el código evaluado es el mismo de producción.
 */
public final class Simulation {

    /** Resultado por estudiante: combinaciones encontradas y tiempo en microsegundos. */
    public record Result(int combinations, long micros) {
    }

    private Simulation() {
    }

    public static Map<String, Result> run(Scenario scenario) throws AppException {
        CatalogReplica replica = new CatalogReplica();
        InMemoryCatalogLog log = new InMemoryCatalogLog(new ApplyLogEntry(replica));
        new IngestCatalog(new IngestState(replica), log).execute(scenario.catalogSeq(), scenario.sections());
        GenerateCombinations generate = new GenerateCombinations(new BacktrackingSolver(), replica, scenario.maxResults());

        Map<String, Result> results = new LinkedHashMap<>();
        for (StudentContext student : scenario.students()) {
            long started = System.nanoTime();
            int found = generate.execute(student).schedules().size();
            results.put(student.id().value(), new Result(found, (System.nanoTime() - started) / 1_000));
        }
        return results;
    }
}
