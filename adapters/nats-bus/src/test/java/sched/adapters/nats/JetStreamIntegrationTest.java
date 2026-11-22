package sched.adapters.nats;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.nats.client.Connection;
import java.io.IOException;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Testcontainers;
import sched.application.AppException;
import sched.application.ApplyLogEntry;
import sched.application.CatalogLogEntry;
import sched.application.CatalogReplica;
import sched.application.Readiness;
import sched.application.ports.EngineMetrics;
import sched.domain.Catalog;
import sched.domain.CatalogChange;
import sched.domain.CatalogEvent;
import sched.domain.Course;
import sched.domain.CourseId;
import sched.domain.EnrollmentRules;
import sched.domain.Section;
import sched.domain.SectionId;
import sched.domain.SectionSeats;
import sched.domain.TimeSlot;
import sched.domain.Weekday;

/**
 * El historial compartido contra un NATS JetStream real (contenedor {@code nats:2 -js}): la carga
 * completa troceada, los cambios, el seguimiento de la réplica, el hueco que la deja desactualizada
 * y la reconexión. Se salta limpiamente si no hay Docker.
 */
@Testcontainers(disabledWithoutDocker = true)
class JetStreamIntegrationTest {

    private static final Duration WAIT = Duration.ofSeconds(60);

    private GenericContainer<?> nats;
    private int port;
    private Connection connection;
    private final List<CatalogFollower> followers = new ArrayList<>();
    private String stream;
    private String prefix;
    private static final AtomicInteger NEXT = new AtomicInteger();

    @BeforeEach
    void startNats() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            port = socket.getLocalPort();
        }
        // Puerto fijo en el host: tras reiniciar el contenedor el cliente se reconecta a la misma dirección.
        nats = new GenericContainer<>("nats:2")
                .withCommand("-js")
                .withExposedPorts(4222)
                .waitingFor(Wait.forLogMessage(".*Server is ready.*", 1));
        nats.setPortBindings(List.of(port + ":4222"));
        nats.start();
        connection = NatsConnector.connect("nats://localhost:" + port, "", "", "", "");
        int n = NEXT.incrementAndGet();
        stream = "TEST_CATALOG_" + n;
        prefix = "test" + n;
    }

    @AfterEach
    void stopNats() throws Exception {
        followers.forEach(CatalogFollower::close);
        if (connection != null) {
            connection.close();
        }
        nats.stop();
    }

    // Datos de prueba: más de 500 grupos para que la carga ocupe varios bloques.

    private static Section section(int i) {
        return new Section(new SectionId(String.format("G%04d", i)), new CourseId(String.format("C%03d", i % 400)),
                List.of(TimeSlot.of(Weekday.values()[i % 5], 420 + (i % 10) * 60, 480 + (i % 10) * 60).orElseThrow()),
                30);
    }

    private static List<SectionSeats> sections(int from, int count) {
        List<SectionSeats> sections = new ArrayList<>();
        for (int i = from; i < from + count; i++) {
            sections.add(new SectionSeats(section(i), i % 7));
        }
        return sections;
    }

    private static List<Course> courses(int count) {
        List<Course> courses = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            courses.add(new Course(new CourseId(String.format("C%03d", i)), 1 + i % 5));
        }
        return courses;
    }

    private static final EnrollmentRules RULES = new EnrollmentRules(22);

    /** La instancia de consulta: réplica, estado de disponibilidad y seguidor del historial. */
    private record Instance(CatalogReplica replica, Readiness readiness, CatalogFollower follower) {
    }

    private Instance follow(EngineMetrics metrics) throws Exception {
        CatalogReplica replica = new CatalogReplica();
        Readiness readiness = new Readiness(replica);
        CatalogFollower follower = new CatalogFollower(connection, stream, new ApplyLogEntry(replica), readiness, metrics);
        followers.add(follower);
        follower.start();
        return new Instance(replica, readiness, follower);
    }

    private JetStreamCatalogLog log() throws Exception {
        return JetStreamCatalogLog.connect(connection, stream, prefix);
    }

    private static void await(String what, BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + WAIT.toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("no ocurrió a tiempo: " + what);
            }
            Thread.sleep(50);
        }
    }

    /** La réplica quedó igual al catálogo esperado: seq, grupos, cupos, créditos y reglas. */
    private static void assertSame(Catalog expected, List<Course> courses, CatalogReplica replica) throws AppException {
        replica.read((actual, stale) -> {
            assertEquals(expected.seq(), actual.seq());
            assertEquals(expected.size(), actual.size());
            assertEquals(expected.rules(), actual.rules());
            for (Course course : courses) {
                assertEquals(expected.creditsOf(course.id()), actual.creditsOf(course.id()), course.id().toString());
            }
            for (int i = 0; i < 1500; i++) {
                SectionId id = new SectionId(String.format("G%04d", i));
                assertEquals(expected.available(id), actual.available(id), id.toString());
                assertEquals(expected.sectionOf(id), actual.sectionOf(id), id.toString());
            }
            return null;
        });
    }

    @Test
    void aChunkedSnapshotAndItsChangesReachTheFollowerIntact() throws Exception {
        Instance instance = follow(EngineMetrics.NOOP);
        JetStreamCatalogLog log = log();
        var sections = sections(0, 1200);
        var courses = courses(600);

        log.append(new CatalogLogEntry.Snapshot(10, sections, courses, RULES));
        var events = List.of(
                new CatalogEvent(11, new CatalogChange.SeatsChanged(new SectionId("G0003"), 0)),
                new CatalogEvent(12, new CatalogChange.SectionUpserted(section(1300), 9)),
                new CatalogEvent(13, new CatalogChange.SectionRemoved(new SectionId("G0010"))));
        for (CatalogEvent event : events) {
            log.append(new CatalogLogEntry.Change(event));
        }

        Catalog expected = Catalog.load(10, sections, courses, RULES);
        for (CatalogEvent event : events) {
            expected.apply(event);
        }
        await("la réplica alcanza el seq 13", () -> instance.replica().isStale() == false
                && seqOf(instance.replica()) == 13);
        await("la instancia está lista", instance.readiness()::isReady);

        assertSame(expected, courses, instance.replica());
        assertTrue(instance.readiness().isReady());
        // 1200 grupos en bloques de 500 = 3 bloques; 600 cursos = 2: la carga ocupó 3 mensajes.
        assertEquals(3 + events.size(), connection.jetStreamManagement().getStreamInfo(stream)
                .getStreamState().getMsgCount());
    }

    @Test
    void aNewSnapshotCompactsTheHistoryAndALateFollowerOnlyReadsIt() throws Exception {
        JetStreamCatalogLog log = log();
        log.append(new CatalogLogEntry.Snapshot(1, sections(0, 700), courses(100), RULES));
        log.append(new CatalogLogEntry.Change(new CatalogEvent(2, new CatalogChange.SeatsChanged(new SectionId("G0001"), 0))));
        var second = sections(0, 1100);
        var secondCourses = courses(400);
        log.append(new CatalogLogEntry.Snapshot(50, second, secondCourses, new EnrollmentRules(18)));

        // Solo queda la última carga (3 bloques): lo anterior se compactó.
        assertEquals(3, connection.jetStreamManagement().getStreamInfo(stream).getStreamState().getMsgCount());

        Instance late = follow(EngineMetrics.NOOP);
        await("el seguidor tardío está listo", late.readiness()::isReady);
        assertSame(Catalog.load(50, second, secondCourses, new EnrollmentRules(18)), secondCourses, late.replica());
    }

    @Test
    void aGapMarksTheReplicaStaleAndASnapshotRecoversIt() throws Exception {
        Instance instance = follow(EngineMetrics.NOOP);
        JetStreamCatalogLog log = log();
        var sections = sections(0, 600);
        log.append(new CatalogLogEntry.Snapshot(1, sections, courses(50), RULES));
        await("la instancia está lista", instance.readiness()::isReady);

        // Un cambio con hueco (seq 5 tras el 1): la réplica no puede darlo por bueno.
        log.append(new CatalogLogEntry.Change(new CatalogEvent(5, new CatalogChange.SeatsChanged(new SectionId("G0001"), 0))));
        await("la réplica queda desactualizada", instance.replica()::isStale);
        assertFalse(instance.readiness().isReady());
        // Aplicar el faltante no la recupera: tras un hueco solo sirve una carga completa.
        log.append(new CatalogLogEntry.Change(new CatalogEvent(2, new CatalogChange.SeatsChanged(new SectionId("G0001"), 0))));
        Thread.sleep(300);
        assertFalse(instance.readiness().isReady());

        log.append(new CatalogLogEntry.Snapshot(9, sections, courses(50), RULES));
        await("la carga completa la recupera", instance.readiness()::isReady);
        assertEquals(9, seqOf(instance.replica()));
    }

    @Test
    void aSyncLostEntryMarksTheReplicaStale() throws Exception {
        Instance instance = follow(EngineMetrics.NOOP);
        JetStreamCatalogLog log = log();
        log.append(new CatalogLogEntry.Snapshot(1, sections(0, 10)));
        await("la instancia está lista", instance.readiness()::isReady);

        log.append(new CatalogLogEntry.SyncLost());

        await("la réplica queda desactualizada", instance.replica()::isStale);
        assertFalse(instance.readiness().isReady());
        // El siguiente cambio sin huecos la recupera (solo se perdió la conexión con la institución).
        log.append(new CatalogLogEntry.Change(new CatalogEvent(2, new CatalogChange.SeatsChanged(new SectionId("G0001"), 0))));
        await("el siguiente cambio la recupera", instance.readiness()::isReady);
    }

    /** Mide los avisos del follower para comprobar el retraso y las relecturas. */
    private static final class Recorder implements EngineMetrics {
        final AtomicLong lag = new AtomicLong(-1);
        final AtomicInteger resyncs = new AtomicInteger();

        @Override
        public void followerLag(long pending) {
            lag.set(pending);
        }

        @Override
        public void followerResync() {
            resyncs.incrementAndGet();
        }
    }

    @Test
    void theFollowerReconnectsAfterNatsRestartsAndKeepsReplicating() throws Exception {
        Recorder recorder = new Recorder();
        Instance instance = follow(recorder);
        JetStreamCatalogLog log = log();
        var sections = sections(0, 800);
        log.append(new CatalogLogEntry.Snapshot(1, sections, courses(100), RULES));
        await("la instancia está lista", instance.readiness()::isReady);
        await("el retraso es 0", () -> recorder.lag.get() == 0);

        // El servidor NATS cae y vuelve con los mismos datos y la misma dirección.
        String id = nats.getContainerId();
        nats.getDockerClient().stopContainerCmd(id).exec();
        await("la instancia deja de estar lista mientras NATS está caído", () -> !instance.readiness().isReady());
        nats.getDockerClient().startContainerCmd(id).exec();

        await("la instancia vuelve a estar lista", instance.readiness()::isReady);
        assertTrue(recorder.resyncs.get() >= 1, "el follower debió releer el historial");

        // Sigue replicando: un cambio nuevo, con la conexión recuperada, llega a la réplica.
        log.append(new CatalogLogEntry.Change(new CatalogEvent(2, new CatalogChange.SeatsChanged(new SectionId("G0001"), 0))));
        await("el cambio posterior a la reconexión llega", () -> seqOf(instance.replica()) == 2);
        int seats = instance.replica().read((catalog, stale) -> catalog.available(new SectionId("G0001")).getAsInt());
        assertEquals(0, seats);
    }

    private static long seqOf(CatalogReplica replica) {
        try {
            return replica.read((catalog, stale) -> catalog.seq());
        } catch (AppException.CatalogUnavailable e) {
            return -1;
        }
    }
}
