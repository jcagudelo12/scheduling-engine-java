package sched.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import sched.adapters.inmemory.InMemoryCatalogLog;
import sched.application.AppException;
import sched.application.ApplyLogEntry;
import sched.application.CatalogLogEntry;
import sched.application.CatalogReplica;
import sched.application.GenerateCombinations;
import sched.application.IngestCatalog;
import sched.application.IngestCatalogEvent;
import sched.application.IngestState;
import sched.application.Readiness;
import sched.application.SuggestAlternatives;
import sched.application.ports.EngineMetrics;
import sched.domain.CatalogChange;
import sched.domain.CatalogEvent;
import sched.domain.CourseId;
import sched.domain.Section;
import sched.domain.SectionId;
import sched.domain.SectionSeats;
import sched.domain.StudentContext;
import sched.domain.StudentId;
import sched.domain.TimeSlot;
import sched.domain.Weekday;
import sched.solver.ForwardCheckingSolver;

/** Las métricas del motor se registran con los nombres y las etiquetas que documenta la arquitectura. */
class MicrometerEngineMetricsTest {

    private MeterRegistry registry;
    private EngineMetrics metrics;
    private CatalogReplica replica;
    private ApplyLogEntry apply;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        metrics = new MicrometerEngineMetrics(registry);
        replica = new CatalogReplica();
        apply = new ApplyLogEntry(replica);
    }

    private static Section section(String id, String course, int startMinute) {
        return new Section(new SectionId(id), new CourseId(course),
                List.of(TimeSlot.of(Weekday.MONDAY, startMinute, startMinute + 60).orElseThrow()), 30);
    }

    private void loadCatalog() throws AppException {
        apply.execute(new CatalogLogEntry.Snapshot(1, List.of(
                new SectionSeats(section("MAT-01", "MAT", 420), 5),
                new SectionSeats(section("FIS-01", "FIS", 600), 5))));
    }

    private static StudentContext student() {
        return new StudentContext(new StudentId("est-001"), List.of(new CourseId("MAT"), new CourseId("FIS")));
    }

    private double count(String name, String tag, String value) {
        return registry.get(name).tag(tag, value).counter().count();
    }

    @Test
    void combinationsRecordDurationNodesAndComplete() throws Exception {
        loadCatalog();
        var generate = new GenerateCombinations(new ForwardCheckingSolver(), replica, 5, Duration.ofSeconds(5), metrics);

        assertEquals(1, generate.execute(student()).schedules().size());

        var timer = registry.get("sched.search.duration").tag("operation", "combinations").timer();
        assertEquals(1, timer.count());
        assertEquals(1, registry.get("sched.search.nodes").tag("operation", "combinations").summary().count());
        assertEquals(0, count("sched.search.incomplete", "operation", "combinations"));
        assertEquals(0, count("sched.search.empty", "operation", "combinations"));
    }

    @Test
    void aSearchCutByTheTimeLimitCountsAsIncomplete() throws Exception {
        loadCatalog();
        // Tiempo máximo de cero: la búsqueda se corta de inmediato (ADR 0009).
        var generate = new GenerateCombinations(new ForwardCheckingSolver(), replica, 5, Duration.ZERO, metrics);
        var alternatives = new SuggestAlternatives(new ForwardCheckingSolver(), replica, 5, Duration.ZERO, metrics);

        assertEquals(false, generate.execute(student()).complete());
        assertEquals(false, alternatives.execute(student(), java.util.Map.of()).complete());

        assertEquals(1, count("sched.search.incomplete", "operation", "combinations"));
        assertEquals(1, count("sched.search.incomplete", "operation", "alternatives"));
        assertEquals(1, registry.get("sched.search.duration").tag("operation", "alternatives").timer().count());
    }

    @Test
    void anEmptyResultIsCounted() throws Exception {
        // Dos cursos cuyos únicos grupos se cruzan: ninguna combinación.
        apply.execute(new CatalogLogEntry.Snapshot(1, List.of(
                new SectionSeats(section("MAT-01", "MAT", 420), 5),
                new SectionSeats(section("FIS-01", "FIS", 420), 5))));
        var generate = new GenerateCombinations(new ForwardCheckingSolver(), replica, 5, Duration.ofSeconds(5), metrics);

        assertEquals(0, generate.execute(student()).schedules().size());

        assertEquals(1, count("sched.search.empty", "operation", "combinations"));
    }

    @Test
    void searchDurationPublishesPercentilesAndTheSloBucket() {
        metrics.searchFinished(EngineMetrics.Operation.COMBINATIONS, Duration.ofMillis(30), 1, true, 10);

        var quantiles = registry.get("sched.search.duration.quantiles").timer().takeSnapshot();
        var percentiles = java.util.Arrays.stream(quantiles.percentileValues()).map(p -> p.percentile()).toList();
        assertEquals(List.of(0.5, 0.95, 0.99), percentiles);
        var snapshot = registry.get("sched.search.duration").timer().takeSnapshot();
        var buckets = java.util.Arrays.stream(snapshot.histogramCounts())
                .map(c -> c.bucket(java.util.concurrent.TimeUnit.MILLISECONDS)).toList();
        org.junit.jupiter.api.Assertions.assertTrue(buckets.contains(200.0), buckets.toString());
    }

    @Test
    void unknownNodeCountIsNotRecorded() {
        metrics.searchFinished(EngineMetrics.Operation.ALTERNATIVES, Duration.ofMillis(1), 2, true, -1);

        assertEquals(0, registry.get("sched.search.nodes").tag("operation", "alternatives").summary().count());
    }

    @Test
    void ingestOutcomesAreCounted() throws Exception {
        var log = new InMemoryCatalogLog(apply);
        var ingest = new IngestState(replica);
        new IngestCatalog(ingest, log, metrics).execute(1, List.of(new SectionSeats(section("MAT-01", "MAT", 420), 5)));
        var events = new IngestCatalogEvent(ingest, log, metrics);

        events.execute(new CatalogEvent(2, new CatalogChange.SeatsChanged(new SectionId("MAT-01"), 4)));
        events.execute(new CatalogEvent(2, new CatalogChange.SeatsChanged(new SectionId("MAT-01"), 4)));
        assertThrows(AppException.SequenceGap.class,
                () -> events.execute(new CatalogEvent(9, new CatalogChange.SeatsChanged(new SectionId("MAT-01"), 3))));

        assertEquals(1, registry.get("sched.ingest.snapshots").counter().count());
        assertEquals(1, count("sched.ingest.events", "outcome", "applied"));
        assertEquals(1, count("sched.ingest.events", "outcome", "duplicate"));
        assertEquals(1, count("sched.ingest.events", "outcome", "gap"));
        assertEquals(0, count("sched.ingest.events", "outcome", "failed"));
        assertEquals(1, registry.get("sched.ingest.sync_lost").counter().count());
    }

    @Test
    void followerLagAndResyncs() {
        metrics.followerLag(42);
        metrics.followerResync();

        assertEquals(42, registry.get("sched.follower.lag").gauge().value());
        assertEquals(1, registry.get("sched.follower.resyncs").counter().count());
        metrics.followerLag(0);
        assertEquals(0, registry.get("sched.follower.lag").gauge().value());
    }

    @Test
    void replicaGaugesFollowSeqAndStaleState() throws Exception {
        var readiness = new Readiness(replica);
        new ReplicaMeters(replica, readiness).bindTo(registry);

        assertEquals(-1, registry.get("sched.replica.seq").gauge().value());
        assertEquals(0, registry.get("sched.replica.stale").gauge().value());
        assertEquals(0, registry.get("sched.replica.ready").gauge().value());

        loadCatalog();
        readiness.markCaughtUp();
        assertEquals(1, registry.get("sched.replica.seq").gauge().value());
        assertEquals(0, registry.get("sched.replica.stale").gauge().value());
        assertEquals(1, registry.get("sched.replica.ready").gauge().value());

        apply.execute(new CatalogLogEntry.SyncLost());
        assertEquals(1, registry.get("sched.replica.stale").gauge().value());
        assertEquals(0, registry.get("sched.replica.ready").gauge().value());
    }
}
