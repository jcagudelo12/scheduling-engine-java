package sched.application;

import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import sched.application.ports.CatalogLog;
import sched.domain.Course;
import sched.domain.EnrollmentRules;
import sched.domain.SectionSeats;

/** Caso de uso: la institución envía el catálogo completo (al conectarse o para resincronizar). */
public final class IngestCatalog {

    private static final Logger log = LoggerFactory.getLogger(IngestCatalog.class);

    private final IngestState ingest;
    private final CatalogLog catalogLog;

    public IngestCatalog(IngestState ingest, CatalogLog catalogLog) {
        this.ingest = ingest;
        this.catalogLog = catalogLog;
    }

    /** Carga sin créditos ni reglas del periodo. */
    public int execute(long seq, List<SectionSeats> sections) throws AppException {
        return execute(seq, sections, List.of(), EnrollmentRules.NONE);
    }

    /**
     * {@code sections} trae cada grupo con sus cupos disponibles; {@code courses}, los créditos
     * de cada asignatura; {@code rules}, las reglas del periodo. Devuelve cuántos grupos se cargaron.
     */
    public int execute(long seq, List<SectionSeats> sections, List<Course> courses, EnrollmentRules rules)
            throws AppException {
        try {
            catalogLog.append(new CatalogLogEntry.Snapshot(seq, sections, courses, rules));
        } catch (PortException e) {
            throw new AppException.PortFailure(e);
        }
        ingest.reset(seq);
        log.info("carga completa escrita en el historial: seq={} grupos={} cursos={} max_creditos={}",
                seq, sections.size(), courses.size(), rules.maxCredits());
        return sections.size();
    }
}
