package sched.adapters.nats;

import io.nats.client.Connection;
import io.nats.client.JetStream;
import io.nats.client.JetStreamApiException;
import io.nats.client.JetStreamManagement;
import io.nats.client.PurgeOptions;
import io.nats.client.api.StorageType;
import io.nats.client.api.StreamConfiguration;
import java.io.IOException;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import sched.application.CatalogLogEntry;
import sched.application.PortException;
import sched.application.ports.CatalogLog;
import sched.domain.Course;
import sched.domain.EnrollmentRules;
import sched.domain.SectionSeats;
import sched.proto.Convert;
import sched.proto.engine.v1.SnapshotChunk;
import sched.proto.engine.v1.SyncLost;

/**
 * Implementa {@link CatalogLog} sobre NATS JetStream. Sujetos {@code <prefijo>.catalog.snapshot},
 * {@code .change} y {@code .sync}, con carga útil {@code engine.v1.CatalogLogEntry} (protobuf).
 */
public final class JetStreamCatalogLog implements CatalogLog {

    private static final Logger log = LoggerFactory.getLogger(JetStreamCatalogLog.class);

    /** Grupos (y, aparte, cursos) por mensaje en una carga completa, para no superar el tamaño máximo de NATS. */
    private static final int SECTIONS_PER_CHUNK = 500;

    private final JetStream js;
    private final JetStreamManagement jsm;
    private final String streamName;
    private final String prefix;

    private JetStreamCatalogLog(Connection connection, String streamName, String prefix) throws IOException {
        this.js = connection.jetStream();
        this.jsm = connection.jetStreamManagement();
        this.streamName = streamName;
        this.prefix = prefix;
    }

    /** Crea el stream si no existe. */
    public static JetStreamCatalogLog connect(Connection connection, String streamName, String prefix)
            throws IOException, JetStreamApiException {
        JetStreamCatalogLog catalogLog = new JetStreamCatalogLog(connection, streamName, prefix);
        try {
            catalogLog.jsm.getStreamInfo(streamName);
        } catch (JetStreamApiException notFound) {
            catalogLog.jsm.addStream(StreamConfiguration.builder()
                    .name(streamName)
                    .subjects(prefix + ".catalog.>")
                    .storageType(StorageType.File)
                    .build());
        }
        return catalogLog;
    }

    @Override
    public void append(CatalogLogEntry entry) throws PortException {
        var builder = sched.proto.engine.v1.CatalogLogEntry.newBuilder();
        switch (entry) {
            case CatalogLogEntry.Snapshot(long seq, List<SectionSeats> sections, List<Course> courses, EnrollmentRules rules) ->
                    appendSnapshot(seq, sections, courses, rules);
            case CatalogLogEntry.Change(var event) ->
                    publish("change", builder.setChange(Convert.catalogEventToPb(event)).build());
            case CatalogLogEntry.SyncLost() -> publish("sync", builder.setSyncLost(
                    SyncLost.newBuilder().setReason("se perdió la sincronía con la institución")).build());
        }
    }

    private void appendSnapshot(long seq, List<SectionSeats> sections, List<Course> courses, EnrollmentRules rules)
            throws PortException {
        // Los bloques alcanzan para repartir tanto los grupos como los cursos.
        int total = Math.max(1, Math.max(chunksFor(sections.size()), chunksFor(courses.size())));
        long firstSequence = -1;
        for (int index = 0; index < total; index++) {
            var chunk = SnapshotChunk.newBuilder().setSeq(seq).setIndex(index).setTotal(total);
            for (SectionSeats s : slice(sections, index)) {
                chunk.addSections(Convert.sectionToPb(s.section(), s.available()));
            }
            for (Course course : slice(courses, index)) {
                chunk.addCourses(Convert.courseToPb(course));
            }
            if (index == 0) {
                chunk.setRules(Convert.rulesToPb(rules));
            }
            long sequence = publish("snapshot",
                    sched.proto.engine.v1.CatalogLogEntry.newBuilder().setSnapshotChunk(chunk).build());
            if (firstSequence < 0) {
                firstSequence = sequence;
            }
        }
        purgeBefore(firstSequence);
    }

    private static int chunksFor(int items) {
        return (items + SECTIONS_PER_CHUNK - 1) / SECTIONS_PER_CHUNK;
    }

    /** Elementos que le tocan al bloque {@code index}; vacío si la lista ya se agotó. */
    private static <T> List<T> slice(List<T> items, int index) {
        int from = Math.min(items.size(), index * SECTIONS_PER_CHUNK);
        return items.subList(from, Math.min(items.size(), from + SECTIONS_PER_CHUNK));
    }

    private long publish(String kind, sched.proto.engine.v1.CatalogLogEntry entry) throws PortException {
        try {
            return js.publish(prefix + ".catalog." + kind, entry.toByteArray()).getSeqno();
        } catch (IOException | JetStreamApiException e) {
            throw new PortException(PortException.Kind.UNAVAILABLE, e.getMessage(), e);
        }
    }

    /**
     * Borra lo anterior a la última carga completa: una instancia nueva solo necesita esa carga y
     * los cambios posteriores.
     */
    private void purgeBefore(long sequence) {
        try {
            jsm.purgeStream(streamName, PurgeOptions.builder().sequence(sequence).build());
        } catch (IOException | JetStreamApiException e) {
            // No es crítico: el historial queda más largo, pero sigue siendo correcto.
            log.warn("no se pudo compactar el historial del catálogo: {}", e.getMessage());
        }
    }
}
