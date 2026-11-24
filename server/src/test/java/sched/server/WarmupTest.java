package sched.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import sched.adapters.http.RequestLimits;
import sched.application.ApplyLogEntry;
import sched.application.CatalogLogEntry;
import sched.application.CatalogReplica;
import sched.application.Readiness;
import sched.domain.CourseId;
import sched.domain.Section;
import sched.domain.SectionId;
import sched.domain.SectionSeats;
import sched.domain.TimeSlot;
import sched.domain.Weekday;
import sched.solver.ForwardCheckingSolver;
import sched.solver.Problem;
import sched.solver.SearchResult;
import sched.solver.Solver;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.json.JsonMapper;

/** El calentador: criterio de parada, catálogo vacío, fallos, interrupción y una sola pasada por proceso. */
class WarmupTest {

    private static final JsonMapper JSON = JsonMapper.builder()
            .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE).build();
    private static final Duration WINDOW = Duration.ofMillis(40);

    private final CatalogReplica replica = new CatalogReplica();
    private final Readiness readiness = new Readiness(replica, true);
    private Warmup warmup;

    @AfterEach
    void stop() {
        if (warmup != null) {
            warmup.stop();
        }
    }

    /** {@code courses} cursos de 3 grupos cada uno, en días y horas distintos para que haya combinaciones. */
    private void loadCatalog(int courses) throws Exception {
        List<SectionSeats> sections = new ArrayList<>();
        for (int c = 0; c < courses; c++) {
            for (int g = 0; g < 3; g++) {
                Section section = new Section(new SectionId("C" + c + "-" + g), new CourseId("C" + c),
                        List.of(TimeSlot.of(Weekday.values()[(c + g) % 5], 420 + 120 * ((c + 2 * g) % 6), 540 + 120 * ((c + 2 * g) % 6))
                                .orElseThrow()), 30);
                sections.add(new SectionSeats(section, 10));
            }
        }
        new ApplyLogEntry(replica).execute(new CatalogLogEntry.Snapshot(1, sections));
        readiness.markCaughtUp();
    }

    private Warmup warmup(WarmupSettings settings, Solver solver, boolean jwt, java.util.function.LongSupplier jit) {
        return warmup(settings, solver, jwt, jit, null);
    }

    private Warmup warmup(WarmupSettings settings, Solver solver, boolean jwt, java.util.function.LongSupplier jit,
            java.util.function.IntSupplier httpPort) {
        warmup = new Warmup(settings, solver, replica, readiness, JSON, RequestLimits.DEFAULTS, 5, 5,
                Duration.ofMillis(150), jwt, httpPort, WINDOW, jit);
        return warmup;
    }

    private static WarmupSettings settings(long minMs, long maxMs) {
        return new WarmupSettings(true, false, Duration.ofMillis(minMs), Duration.ofMillis(maxMs), 2);
    }

    @Test
    void notReadyUntilItFinishesAndStopsAtTheMaximumWhileTheJitKeepsCompiling() throws Exception {
        loadCatalog(8);
        AtomicLong busy = new AtomicLong();
        Warmup w = warmup(settings(0, 1_500), new ForwardCheckingSolver(), true, () -> busy.addAndGet(1_000));

        assertFalse(readiness.isReady());
        w.start();
        // Mientras calienta, el catálogo está al día pero la instancia no está lista.
        assertTrue(awaitTrue(() -> w.searches() > 0));
        assertTrue(readiness.isCatalogReady());
        assertFalse(readiness.isReady());

        assertTrue(w.awaitDone(Duration.ofSeconds(20)));
        assertEquals("tiempo máximo", w.report().reason());
        assertTrue(w.report().elapsed().toMillis() >= 1_500, w.report().toString());
        assertTrue(w.report().searches() > 0);
        assertTrue(readiness.isReady());
    }

    @Test
    void stopsEarlyOnceTheJitIsQuietButNotBeforeTheMinimum() throws Exception {
        loadCatalog(8);
        Warmup w = warmup(settings(300, 20_000), new ForwardCheckingSolver(), false, () -> 0);
        w.start();
        assertTrue(w.awaitDone(Duration.ofSeconds(20)));
        assertEquals("JIT estable", w.report().reason());
        assertTrue(w.report().elapsed().toMillis() >= 300, "no para antes del mínimo: " + w.report());
        assertTrue(w.report().elapsed().toMillis() < 10_000, w.report().toString());
        assertTrue(readiness.isReady());
    }

    @Test
    void anEmptyOrTooSmallCatalogEndsRightAway() throws Exception {
        loadCatalog(WarmupCases.MIN_COURSES - 1);
        Warmup w = warmup(settings(5_000, 60_000), new ForwardCheckingSolver(), true, () -> 0);
        w.start();
        assertTrue(w.awaitDone(Duration.ofSeconds(10)));
        assertEquals("catálogo sin cursos suficientes", w.report().reason());
        assertEquals(0, w.searches());
        assertTrue(readiness.isReady(), "sin nada que calentar, no se bloquea la instancia");
    }

    @Test
    void aCatalogWithoutAnyCourseAlsoEndsRightAway() throws Exception {
        loadCatalog(0);
        Warmup w = warmup(settings(5_000, 60_000), new ForwardCheckingSolver(), false, () -> 0);
        w.start();
        assertTrue(w.awaitDone(Duration.ofSeconds(10)));
        assertEquals("catálogo sin cursos suficientes", w.report().reason());
        assertTrue(readiness.isReady());
    }

    @Test
    void aFailingWarmupIsLoggedAndTheInstanceStillBecomesReady() throws Exception {
        loadCatalog(8);
        AtomicInteger calls = new AtomicInteger();
        Solver failing = new Solver() {
            @Override
            public SearchResult search(Problem problem) {
                // Deja pasar la preparación de casos y falla en los hilos de trabajo.
                if (calls.incrementAndGet() > 5_000) {
                    throw new IllegalStateException("fallo simulado");
                }
                return new ForwardCheckingSolver().search(problem);
            }
        };
        Warmup w = warmup(settings(0, 30_000), failing, false, () -> 1_000_000L * calls.get());
        w.start();
        assertTrue(w.awaitDone(Duration.ofSeconds(30)));
        assertEquals("falló un hilo de calentamiento", w.report().reason());
        assertTrue(readiness.isReady());
    }

    @Test
    void anExceptionWhilePreparingAlsoMarksItWarmed() throws Exception {
        loadCatalog(8);
        Solver failing = new Solver() {
            @Override
            public SearchResult search(Problem problem) {
                throw new IllegalStateException("fallo simulado");
            }
        };
        Warmup w = warmup(settings(0, 30_000), failing, false, () -> 0);
        w.start();
        assertTrue(w.awaitDone(Duration.ofSeconds(10)));
        assertEquals("falló: IllegalStateException", w.report().reason());
        assertTrue(readiness.isReady());
    }

    @Test
    void shutdownInterruptsItCleanly() throws Exception {
        loadCatalog(8);
        AtomicLong busy = new AtomicLong();
        Warmup w = warmup(settings(0, 600_000), new ForwardCheckingSolver(), true, () -> busy.addAndGet(1_000));
        w.start();
        assertTrue(awaitTrue(() -> w.searches() > 0));
        assertTrue(w.isRunning());
        long started = System.nanoTime();
        w.stop();
        assertFalse(w.isRunning());
        assertTrue(Duration.ofNanos(System.nanoTime() - started).toSeconds() < 5);
    }

    @Test
    void itRunsOnlyOnceEvenIfStartedAgainOrTheReplicaGoesStale() throws Exception {
        loadCatalog(8);
        Warmup w = warmup(settings(0, 300), new ForwardCheckingSolver(), false, () -> 1_000_000L);
        w.start();
        w.start(); // un segundo start no lanza otro hilo
        assertTrue(w.awaitDone(Duration.ofSeconds(20)));
        long searches = w.searches();
        Warmup.Report first = w.report();

        new ApplyLogEntry(replica).execute(new CatalogLogEntry.SyncLost());
        assertFalse(readiness.isReady());
        loadCatalog(8);
        assertTrue(readiness.isReady(), "se recupera sin volver a calentar");
        w.start();
        Thread.sleep(150);
        assertEquals(searches, w.searches());
        assertEquals(first, w.report());
    }

    @Test
    void theHttpWarmupOnlyAsksForTheHealthProbeOnLocalhost() throws Exception {
        loadCatalog(8);
        var paths = new java.util.concurrent.CopyOnWriteArrayList<String>();
        var server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("localhost", 0), 0);
        server.createContext("/", exchange -> {
            paths.add(exchange.getRequestMethod() + " " + exchange.getRequestURI().getPath()
                    + (exchange.getRequestHeaders().containsKey("Authorization") ? " con-token" : ""));
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        server.start();
        try {
            var settings = new WarmupSettings(true, true, Duration.ofMillis(1_500), Duration.ofMillis(5_000), 2);
            Warmup w = warmup(settings, new ForwardCheckingSolver(), true, () -> 1_000_000L,
                    () -> server.getAddress().getPort());
            w.start();
            assertTrue(w.awaitDone(Duration.ofSeconds(20)));
        } finally {
            server.stop(0);
        }
        assertFalse(paths.isEmpty());
        assertEquals(java.util.Set.of("GET /health"), new java.util.HashSet<>(paths), "ni rutas de la API ni tokens");
    }

    @Test
    void waitsForTheCatalogBeforeWarming() throws Exception {
        Warmup w = warmup(settings(0, 300), new ForwardCheckingSolver(), false, () -> 1_000_000L);
        w.start();
        Thread.sleep(200);
        assertEquals(0, w.searches());
        assertNull(w.report());
        assertFalse(readiness.isReady());
        loadCatalog(8);
        assertTrue(w.awaitDone(Duration.ofSeconds(20)));
        assertNotNull(w.report());
        assertTrue(readiness.isReady());
    }

    @Test
    void theSyntheticCasesAreDeterministicAndRealisticallyShaped() throws Exception {
        loadCatalog(10);
        var generate = new sched.application.GenerateCombinations(new ForwardCheckingSolver(), replica, 5, null);
        var a = WarmupCases.generate(replica, generate, RequestLimits.DEFAULTS, 64, Warmup.SEED);
        var b = WarmupCases.generate(replica, generate, RequestLimits.DEFAULTS, 64, Warmup.SEED);
        assertEquals(a, b, "misma semilla, mismas entradas");
        assertFalse(a.isEmpty());
        for (var c : a) {
            assertTrue(c.courses().size() >= 4 && c.courses().size() <= 7, c.courses().toString());
            assertEquals(c.courses().size(), c.selection().size());
            assertTrue(c.selection().values().containsAll(c.enrolled()));
            assertTrue(c.studentId().startsWith("warmup-"));
        }
        assertTrue(a.stream().anyMatch(c -> !c.avoid().isEmpty()));
        assertTrue(a.stream().anyMatch(c -> c.avoid().isEmpty()));
    }

    private static boolean awaitTrue(java.util.function.BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return true;
            }
            Thread.sleep(10);
        }
        return false;
    }
}
