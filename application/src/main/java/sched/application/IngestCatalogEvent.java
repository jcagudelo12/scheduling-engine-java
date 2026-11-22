package sched.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import sched.application.ports.CatalogLog;
import sched.application.ports.EngineMetrics;
import sched.domain.ApplyOutcome;
import sched.domain.CatalogEvent;

/** Caso de uso: la institución notifica un cambio incremental del catálogo. */
public final class IngestCatalogEvent {

    private static final Logger log = LoggerFactory.getLogger(IngestCatalogEvent.class);

    private final IngestState ingest;
    private final CatalogLog catalogLog;
    private final EngineMetrics metrics;

    public IngestCatalogEvent(IngestState ingest, CatalogLog catalogLog) {
        this(ingest, catalogLog, EngineMetrics.NOOP);
    }

    public IngestCatalogEvent(IngestState ingest, CatalogLog catalogLog, EngineMetrics metrics) {
        this.ingest = ingest;
        this.catalogLog = catalogLog;
        this.metrics = metrics;
    }

    /**
     * Valida el orden y escribe el cambio en el historial.
     *
     * @throws AppException.SequenceGap se perdieron eventos; la institución debe enviar una carga
     *     completa y todas las réplicas quedan desactualizadas mientras tanto
     * @throws AppException.CatalogUnavailable aún no hay carga completa
     * @throws AppException.PortFailure no se pudo escribir; el {@code seq} no se consume y puede
     *     reenviarse
     */
    public ApplyOutcome execute(CatalogEvent event) throws AppException {
        // Admitir y escribir van juntos bajo el candado de la ingesta (ver IngestState). El seq
        // solo se consume si la escritura tuvo éxito: si falla, la institución reenvía el mismo
        // seq y se escribe de verdad, en vez de tratarse como duplicado.
        synchronized (ingest) {
            IngestState.Admission admission;
            try {
                admission = ingest.admit(event.seq());
            } catch (AppException.SequenceGap gap) {
                log.warn("hueco en los cambios de la institución: {}", gap.getMessage());
                metrics.ingestEvent(EngineMetrics.IngestOutcome.GAP);
                notifySyncLost();
                throw gap;
            }
            if (admission == IngestState.Admission.DUPLICATE) {
                metrics.ingestEvent(EngineMetrics.IngestOutcome.DUPLICATE);
                return ApplyOutcome.DUPLICATE;
            }

            try {
                catalogLog.append(new CatalogLogEntry.Change(event));
            } catch (PortException e) {
                metrics.ingestEvent(EngineMetrics.IngestOutcome.FAILED);
                throw new AppException.PortFailure(e);
            }
            ingest.commit(event.seq());
            metrics.ingestEvent(EngineMetrics.IngestOutcome.APPLIED);
            return ApplyOutcome.APPLIED;
        }
    }

    /** Se cerró el canal de cambios: ya no hay garantía de estar al día. */
    public void connectionLost() {
        log.warn("se perdió la conexión de cambios con la institución");
        notifySyncLost();
    }

    private void notifySyncLost() {
        // Bajo el mismo candado para no intercalarse entre un cambio y su escritura.
        synchronized (ingest) {
            metrics.syncLost();
            try {
                catalogLog.append(new CatalogLogEntry.SyncLost());
            } catch (PortException e) {
                log.error("no se pudo avisar la pérdida de sincronía: {}", e.getMessage());
            }
        }
    }
}
