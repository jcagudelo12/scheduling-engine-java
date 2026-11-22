package sched.server;

import io.grpc.protobuf.services.ProtoReflectionServiceV1;
import io.nats.client.Connection;
import io.nats.client.Nats;
import java.time.Duration;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import sched.adapters.grpc.catalog.CatalogGrpcService;
import sched.adapters.grpc.events.CatalogEventsGrpcService;
import sched.adapters.http.AlternativesController;
import sched.adapters.http.BodySizeLimitFilter;
import sched.adapters.http.CombinationsController;
import sched.adapters.http.HealthController;
import sched.adapters.http.HttpExceptionHandler;
import sched.adapters.http.RequestLimits;
import sched.adapters.nats.CatalogFollower;
import sched.adapters.nats.JetStreamCatalogLog;
import sched.application.ApplyLogEntry;
import sched.application.CatalogReplica;
import sched.application.GenerateCombinations;
import sched.application.IngestCatalog;
import sched.application.IngestCatalogEvent;
import sched.application.IngestState;
import sched.application.Readiness;
import sched.application.SuggestAlternatives;
import sched.solver.BacktrackingSolver;
import sched.solver.ForwardCheckingSolver;
import sched.solver.Solver;

/** Composición: crea los adaptadores concretos y los inyecta en los casos de uso. */
@Configuration
class EngineConfig {

    private static final String SERVES_QUERIES = "'${sched.role}' == 'query' or '${sched.role}' == 'all'";
    private static final String INGESTS = "'${sched.role}' == 'ingest' or '${sched.role}' == 'all'";

    @Bean
    Role role(@Value("${sched.role}") String role) {
        return Role.parse(role);
    }

    // Historial compartido y réplica local (todas las instancias).

    @Bean(destroyMethod = "close")
    Connection nats(@Value("${sched.nats.url}") String url) throws Exception {
        return Nats.connect(url);
    }

    @Bean
    JetStreamCatalogLog catalogLog(
            Connection nats,
            @Value("${sched.nats.stream}") String stream,
            @Value("${sched.nats.subject-prefix}") String prefix) throws Exception {
        return JetStreamCatalogLog.connect(nats, stream, prefix);
    }

    @Bean
    CatalogReplica replica() {
        return new CatalogReplica();
    }

    @Bean
    Readiness readiness(CatalogReplica replica) {
        return new Readiness(replica);
    }

    @Bean(initMethod = "start", destroyMethod = "close")
    CatalogFollower follower(
            Connection nats,
            @Value("${sched.nats.stream}") String stream,
            CatalogReplica replica,
            Readiness readiness,
            JetStreamCatalogLog catalogLog) {
        // catalogLog se inyecta para garantizar que el stream exista antes de leerlo.
        return new CatalogFollower(nats, stream, new ApplyLogEntry(replica), readiness);
    }

    // HTTP: sondas siempre; consultas de estudiantes según el rol.

    @Bean
    HealthController healthController(Readiness readiness) {
        return new HealthController(readiness);
    }

    @Bean
    Solver solver(@Value("${sched.solver}") String name) {
        return switch (name) {
            case "forward-checking" -> new ForwardCheckingSolver();
            case "backtracking" -> new BacktrackingSolver();
            default -> throw new IllegalArgumentException(
                    "SCHED_SOLVER desconocido: '" + name + "' (use forward-checking o backtracking)");
        };
    }

    /** Límites de tamaño de las solicitudes de consulta (valores por defecto en application.properties). */
    @Bean
    RequestLimits requestLimits(
            @Value("${sched.limits.max-courses}") int maxCourses,
            @Value("${sched.limits.max-avoid}") int maxAvoid,
            @Value("${sched.limits.max-id-length}") int maxIdLength,
            @Value("${sched.limits.max-body-bytes}") long maxBodyBytes) {
        return new RequestLimits(maxCourses, maxAvoid, maxIdLength, maxBodyBytes);
    }

    /** Spring Boot lo registra solo como filtro del servlet. */
    @Bean
    BodySizeLimitFilter bodySizeLimitFilter(RequestLimits limits) {
        return new BodySizeLimitFilter(limits.maxBodyBytes());
    }

    @Bean
    HttpExceptionHandler httpExceptionHandler() {
        return new HttpExceptionHandler();
    }

    @Bean
    @ConditionalOnExpression(SERVES_QUERIES)
    CombinationsController combinationsController(
            Solver solver,
            CatalogReplica replica,
            @Value("${sched.max-combinations}") int maxCombinations,
            @Value("${sched.search-timeout-ms}") long timeoutMs,
            RequestLimits limits) {
        return new CombinationsController(
                new GenerateCombinations(solver, replica, maxCombinations, searchTimeout(timeoutMs)), limits);
    }

    @Bean
    @ConditionalOnExpression(SERVES_QUERIES)
    AlternativesController alternativesController(
            Solver solver,
            CatalogReplica replica,
            @Value("${sched.max-alternatives}") int maxAlternatives,
            @Value("${sched.search-timeout-ms}") long timeoutMs,
            RequestLimits limits) {
        return new AlternativesController(
                new SuggestAlternatives(solver, replica, maxAlternatives, searchTimeout(timeoutMs)), limits);
    }

    /** {@code SCHED_SEARCH_TIMEOUT_MS = 0} desactiva el tiempo máximo (ADR 0009). */
    private static Duration searchTimeout(long millis) {
        if (millis < 0) {
            throw new IllegalArgumentException("SCHED_SEARCH_TIMEOUT_MS no puede ser negativo: " + millis);
        }
        return millis == 0 ? null : Duration.ofMillis(millis);
    }

    // gRPC de ingesta, según el rol.

    @Bean(initMethod = "start", destroyMethod = "stop")
    @ConditionalOnExpression(INGESTS)
    IngestGrpcServer ingestGrpcServer(
            @Value("${sched.grpc.port}") int port,
            CatalogReplica replica,
            Readiness readiness,
            JetStreamCatalogLog catalogLog) {
        IngestState ingest = new IngestState(replica);
        return new IngestGrpcServer(port, readiness, List.of(
                new CatalogGrpcService(new IngestCatalog(ingest, catalogLog)),
                new CatalogEventsGrpcService(new IngestCatalogEvent(ingest, catalogLog)),
                // Reflection: grpcurl, grpcui y Postman descubren los servicios sin los .proto.
                ProtoReflectionServiceV1.newInstance()));
    }
}
