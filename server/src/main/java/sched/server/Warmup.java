package sched.server;

import java.lang.management.CompilationMXBean;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.IntSupplier;
import java.util.function.LongSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import sched.adapters.http.AlternativesController;
import sched.adapters.http.AlternativesController.AlternativesRequestDto;
import sched.adapters.http.CombinationsController;
import sched.adapters.http.CombinationsController.StudentContextDto;
import sched.adapters.http.RequestLimits;
import sched.adapters.http.VerifiedStudent;
import sched.adapters.http.auth.InvalidTokenException;
import sched.adapters.http.auth.JwtVerifier;
import sched.application.AppException;
import sched.application.CatalogReplica;
import sched.application.GenerateCombinations;
import sched.application.Readiness;
import sched.application.SuggestAlternatives;
import sched.application.ports.EngineMetrics;
import sched.solver.Solver;
import tools.jackson.databind.json.JsonMapper;

/**
 * Calienta la JVM antes de que {@code /ready} responda 200.
 *
 * <p>Con una JVM fría, el compilador C2 consume ~0,6 de 2 núcleos durante ~30 s; si el pod recibe
 * el pico en ese momento, las solicitudes se encolan y la latencia se dispara
 * ({@code bench/results/profile-server.md}). Aquí el pod solo se marca listo
 * ({@link Readiness#markWarmedUp()}) cuando el compilador ya se calmó.
 *
 * <p>En cuanto la réplica tiene un catálogo al día, un hilo propio lanza varios hilos de trabajo que
 * repiten, <b>en proceso y sin red</b>, el camino pesado de una consulta con entradas sintéticas
 * ({@link WarmupCases}) sacadas del catálogo:
 *
 * <ul>
 *   <li>los casos de uso reales ({@link GenerateCombinations}, {@link SuggestAlternatives}) con el
 *       mismo solver, tope de resultados y tiempo máximo que producción, pero con
 *       {@link EngineMetrics#NOOP}: no tocan las métricas de búsqueda;
 *   <li>los controladores HTTP (mapeo de los DTO) y la (de)serialización JSON con el mismo
 *       {@link JsonMapper} que usa Spring MVC;
 *   <li>con {@code SCHED_WARMUP_HTTP=on}, solicitudes {@code GET /health} a {@code localhost} (ruta pública de la sonda, sin
 *       token ni atajo alguno): calientan Tomcat, los filtros y Spring MVC, que el perfil de la fase fría muestra como
 *       lo que más se sigue compilando tras calentar solo el solver y el JSON. {@code /health} no entra en las métricas
 *       {@code sched_http_*};
 *   <li>con {@code SCHED_AUTH=jwt}, la verificación EdDSA con un {@link JwtVerifier} propio y una
 *       llave efímera (ver {@link WarmupTokens}): no abre ningún atajo en el verificador real.
 * </ul>
 *
 * <p><b>Criterio de parada</b> (adaptativo y acotado): cada ventana ({@link #WINDOW}) se mide cuánto
 * tiempo de compilación JIT se acumuló ({@code CompilationMXBean.getTotalCompilationTime()}). Termina
 * cuando, pasado el mínimo, {@link #QUIET_WINDOWS} ventanas seguidas compilan menos de
 * {@link #QUIET_MS} ms; o al llegar al máximo, pase lo que pase. Se hace una sola vez por proceso:
 * una réplica que queda desactualizada y se recupera no vuelve a calentarse. Si falla con una excepción
 * se registra y la instancia queda marcada como calentada igualmente.
 *
 * <p>Nunca se registran datos de estudiantes ni tokens (los casos son sintéticos). Al apagar el contexto
 * se interrumpe.
 */
final class Warmup implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(Warmup.class);

    /** Semilla fija: mismas entradas en cada arranque con el mismo catálogo. */
    static final long SEED = 20_251_122L;
    /** Casos sintéticos distintos que se reparten entre los hilos. */
    static final int CASES = 512;
    /** Ventana de medición del criterio de parada. */
    static final Duration WINDOW = Duration.ofSeconds(1);
    /** Una ventana es "tranquila" si compiló menos de esto (ms de tiempo de compilación, sumado entre compiladores). */
    static final long QUIET_MS = 30;
    /** Ventanas tranquilas seguidas que hacen falta para parar. */
    static final int QUIET_WINDOWS = 3;
    /** Una de cada {@value} solicitudes sintéticas es de alternativas (el pico: ~20 %). */
    private static final int ALTERNATIVES_EVERY = 5;
    /** Fallos de conexión a localhost tras los cuales un hilo deja de intentar el calentamiento HTTP. */
    private static final int MAX_HTTP_FAILURES = 20;
    private static final Duration CATALOG_POLL = Duration.ofMillis(50);

    /** Resultado del calentamiento, para el log y las métricas. */
    record Report(Duration elapsed, long searches, String reason, long jitMillis) {
    }

    private final WarmupSettings settings;
    private final Readiness readiness;
    private final CatalogReplica replica;
    private final JsonMapper json;
    private final RequestLimits limits;
    private final boolean jwt;
    private final IntSupplier httpPort;
    private final CombinationsController combinations;
    private final AlternativesController alternatives;
    private final GenerateCombinations pool;
    private final Duration window;
    private final LongSupplier compileMillis;

    private final AtomicLong searches = new AtomicLong();
    private volatile boolean stopRequested;
    private volatile Report report;
    private volatile Thread thread;

    Warmup(
            WarmupSettings settings,
            Solver solver,
            CatalogReplica replica,
            Readiness readiness,
            JsonMapper json,
            RequestLimits limits,
            int maxCombinations,
            int maxAlternatives,
            Duration searchTimeout,
            boolean jwt,
            IntSupplier httpPort) {
        this(settings, solver, replica, readiness, json, limits, maxCombinations, maxAlternatives, searchTimeout, jwt,
                httpPort, WINDOW, jitClock());
    }

    /** Con la ventana y el reloj de compilación inyectables, para las pruebas. */
    Warmup(
            WarmupSettings settings,
            Solver solver,
            CatalogReplica replica,
            Readiness readiness,
            JsonMapper json,
            RequestLimits limits,
            int maxCombinations,
            int maxAlternatives,
            Duration searchTimeout,
            boolean jwt,
            IntSupplier httpPort,
            Duration window,
            LongSupplier compileMillis) {
        this.settings = settings;
        this.readiness = readiness;
        this.replica = replica;
        this.json = json;
        this.limits = limits;
        this.jwt = jwt;
        this.httpPort = httpPort;
        this.window = window;
        this.compileMillis = compileMillis;
        // Los mismos casos de uso y controladores que producción, con métricas apagadas.
        var generate = new GenerateCombinations(solver, replica, maxCombinations, searchTimeout, EngineMetrics.NOOP);
        var suggest = new SuggestAlternatives(solver, replica, maxAlternatives, searchTimeout, EngineMetrics.NOOP);
        this.pool = generate;
        this.combinations = new CombinationsController(generate, limits);
        this.alternatives = new AlternativesController(suggest, limits);
    }

    /** Tiempo de compilación JIT acumulado en ms; si la JVM no lo informa, siempre 0 (se espera al mínimo). */
    private static LongSupplier jitClock() {
        CompilationMXBean bean = ManagementFactory.getCompilationMXBean();
        if (bean == null || !bean.isCompilationTimeMonitoringSupported()) {
            return () -> 0;
        }
        return bean::getTotalCompilationTime;
    }

    /** Resultado una vez terminado; {@code null} mientras tanto. */
    Report report() {
        return report;
    }

    long searches() {
        return searches.get();
    }

    // Ciclo de vida: arranca después del servidor web (fase por defecto) y se detiene antes.

    @Override
    public synchronized void start() {
        if (thread != null) {
            return;
        }
        thread = Thread.ofPlatform().name("sched-warmup").daemon().start(this::run);
    }

    @Override
    public synchronized void stop() {
        stopRequested = true;
        Thread running = thread;
        if (running != null) {
            running.interrupt();
            try {
                running.join(Duration.ofSeconds(5));
            } catch (InterruptedException _) {
                Thread.currentThread().interrupt();
            }
        }
    }

    @Override
    public boolean isRunning() {
        Thread running = thread;
        return running != null && running.isAlive();
    }

    /** Espera a que el hilo de calentamiento termine (para las pruebas). */
    boolean awaitDone(Duration timeout) throws InterruptedException {
        Thread running = thread;
        if (running == null) {
            return false;
        }
        running.join(timeout);
        return !running.isAlive();
    }

    // Hilo coordinador

    private void run() {
        long started = System.nanoTime();
        try {
            while (!readiness.isCatalogReady()) {
                Thread.sleep(CATALOG_POLL);
            }
            started = System.nanoTime();
            report = warmUp();
            log.info("calentamiento terminado: duración={} ms, búsquedas={}, motivo={}, compilación JIT={} ms",
                    report.elapsed().toMillis(), report.searches(), report.reason(), report.jitMillis());
        } catch (InterruptedException _) {
            log.info("calentamiento interrumpido por el apagado");
        } catch (Throwable e) {
            log.warn("el calentamiento falló y se omite; la instancia se marca como calentada: {}: {}",
                    e.getClass().getSimpleName(), e.getMessage());
            report = new Report(since(started), searches.get(), "falló: " + e.getClass().getSimpleName(), 0);
        } finally {
            readiness.markWarmedUp();
        }
    }

    private Report warmUp() throws InterruptedException, AppException.CatalogUnavailable {
        long started = System.nanoTime();
        List<WarmupCases.Case> cases = WarmupCases.generate(replica, pool, limits, CASES, SEED);
        if (cases.isEmpty()) {
            return new Report(since(started), 0, "catálogo sin cursos suficientes", 0);
        }
        WarmupTokens tokens = jwt
                ? WarmupTokens.issue(json, cases.stream().map(WarmupCases.Case::studentId).toList(),
                        cases.stream().map(WarmupCases.Case::courses).toList())
                : null;
        int threads = settings.effectiveThreads();
        log.info("calentamiento iniciado: hilos={}, casos={}, mínimo={} s, máximo={} s, auth={}, http={}",
                threads, cases.size(), settings.min().toSeconds(), settings.max().toSeconds(),
                jwt ? "jwt (llave efímera)" : "none", settings.http() && httpPort != null ? "on" : "off");

        AtomicReference<Throwable> failure = new AtomicReference<>();
        List<Thread> workers = new ArrayList<>(threads);
        for (int w = 0; w < threads; w++) {
            int index = w;
            workers.add(Thread.ofPlatform().name("sched-warmup-" + w).daemon().start(() -> {
                try {
                    work(index, threads, cases, tokens);
                } catch (InterruptedException _) {
                    // Es la forma normal de terminar: el coordinador interrumpe a los hilos al parar.
                } catch (Throwable e) {
                    failure.compareAndSet(null, e);
                }
            }));
        }
        long jitStart = compileMillis.getAsLong();
        String reason;
        try {
            reason = supervise(started, jitStart, failure);
        } finally {
            stopRequested = true;
            for (Thread worker : workers) {
                worker.interrupt();
            }
            for (Thread worker : workers) {
                worker.join(Duration.ofSeconds(5));
            }
        }
        Throwable error = failure.get();
        if (error != null) {
            log.warn("un hilo de calentamiento falló: {}: {}", error.getClass().getSimpleName(), error.getMessage());
        }
        return new Report(since(started), searches.get(), reason, compileMillis.getAsLong() - jitStart);
    }

    /** Mide el JIT por ventanas y decide cuándo parar; devuelve el motivo. */
    private String supervise(long started, long jitStart, AtomicReference<Throwable> failure)
            throws InterruptedException {
        long last = jitStart;
        int quiet = 0;
        while (true) {
            Thread.sleep(window);
            long now = compileMillis.getAsLong();
            long delta = now - last;
            last = now;
            quiet = delta < QUIET_MS ? quiet + 1 : 0;
            Duration elapsed = since(started);
            log.debug("calentamiento: t={} ms, compilación en la ventana={} ms, búsquedas={}",
                    elapsed.toMillis(), delta, searches.get());
            if (failure.get() != null) {
                return "falló un hilo de calentamiento";
            }
            if (elapsed.compareTo(settings.max()) >= 0) {
                return "tiempo máximo";
            }
            if (elapsed.compareTo(settings.min()) >= 0 && quiet >= QUIET_WINDOWS) {
                return "JIT estable";
            }
        }
    }

    // Hilos de trabajo

    private void work(int index, int threads, List<WarmupCases.Case> cases, WarmupTokens tokens) throws Exception {
        HttpClient client = settings.http() && httpPort != null
                ? HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(2)).build()
                : null;
        HttpRequest probe = client == null ? null
                : HttpRequest.newBuilder(URI.create("http://localhost:" + httpPort.getAsInt() + "/health"))
                        .timeout(Duration.ofSeconds(5)).GET().build();
        int httpFailures = 0;
        for (long i = 0; !stopRequested && !Thread.currentThread().isInterrupted(); i++) {
            if (client != null && httpFailures < MAX_HTTP_FAILURES) {
                try {
                    client.send(probe, HttpResponse.BodyHandlers.discarding());
                } catch (IOException e) {
                    if (++httpFailures == MAX_HTTP_FAILURES) {
                        log.warn("el calentamiento HTTP a localhost falla ({}); se omite en este hilo", e.getClass().getSimpleName());
                    }
                }
            }
            int slot = (int) ((i * threads + index) % cases.size());
            WarmupCases.Case c = cases.get(slot);
            VerifiedStudent verified = null;
            if (tokens != null) {
                var claims = verify(tokens, slot);
                verified = new VerifiedStudent(claims.subject(), claims.courses());
            }
            boolean withBody = !jwt;
            try {
                if (i % ALTERNATIVES_EVERY == ALTERNATIVES_EVERY - 1) {
                    var request = new AlternativesRequestDto(withBody ? c.studentId() : null,
                            withBody ? c.courses() : null, c.avoid(), c.selection(), c.enrolled());
                    var parsed = json.readValue(json.writeValueAsBytes(request), AlternativesRequestDto.class);
                    json.writeValueAsBytes(alternatives.alternatives(parsed, verified));
                } else {
                    var request = new StudentContextDto(withBody ? c.studentId() : null,
                            withBody ? c.courses() : null, c.avoid());
                    var parsed = json.readValue(json.writeValueAsBytes(request), StudentContextDto.class);
                    json.writeValueAsBytes(combinations.combinations(parsed, verified));
                }
            } catch (AppException _) {
                // Rechazo de negocio esperable (p. ej. un cupo que cambió): también es camino de producción.
            }
            searches.incrementAndGet();
        }
    }

    private static JwtVerifier.Claims verify(WarmupTokens tokens, int slot)
            throws InvalidTokenException {
        return tokens.verifier().verify(tokens.tokens().get(slot));
    }

    private static Duration since(long startedNanos) {
        return Duration.ofNanos(System.nanoTime() - startedNanos);
    }
}
