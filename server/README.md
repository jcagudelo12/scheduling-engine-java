# server

**El motor ejecutable: arranca Spring Boot y conecta todas las piezas según el rol.** Es el único módulo
que conoce a todos los demás (adaptadores, casos de uso y solver). No contiene reglas de negocio: solo
composición, configuración, métricas y calentamiento.

## Qué hay dentro

Paquete `sched.server` (`src/main/java/sched/server/`):

| Clases | Qué hacen |
|---|---|
| `SchedServer` | El `main` de Spring Boot |
| `EngineConfig` | **El mejor punto de partida**: crea los adaptadores y casos de uso según `SCHED_ROLE` (`ingest`, `query` o `all`), el solver (`SCHED_SOLVER`) y los límites |
| `Role`, `AuthMode` | Los valores válidos de `SCHED_ROLE` y `SCHED_AUTH` (cualquier otro impide arrancar) |
| `IngestGrpcServer`, `GrpcCredentials` | Abren el servidor gRPC de ingesta (puerto 50051) y cargan los certificados de mTLS |
| `Warmup`, `WarmupCases`, `WarmupSettings`, `WarmupTokens`, `WarmupMeters` | El **calentamiento de la JVM** antes de que `/ready` responda 200: consultas sintéticas en proceso ([cómo funciona](../docs/inicio-rapido.md#calentamiento-de-la-jvm)) |
| `MicrometerEngineMetrics`, `ReplicaMeters`, `HttpMetricsFilter`, `LatencyTimer` | Las métricas Prometheus: implementan el puerto `EngineMetrics` y miden la latencia HTTP |
| `src/main/resources/application.properties` | Todas las variables `SCHED_*` con sus valores por defecto |
| `src/main/resources/log-format/` | Los formatos de log `text` y `json` (ECS) |

Se publican dos jars en `server/target/`: **`sched-server-exec.jar`** (el motor completo, el que se ejecuta con
`java -jar`) y `sched-server.jar` (solo las clases del módulo, no ejecutable). Ver
[Jar ejecutable](../docs/conceptos-base.md#jar-ejecutable).

## De qué depende y quién lo usa

- **Depende de:** `application`, `solver`, los cuatro adaptadores principales (`gateway-http`, `grpc-catalog`, `grpc-events`,
  `nats-bus`), Spring Boot (MVC y Actuator), Micrometer con Prometheus, `jnats` y gRPC Netty. `in-memory` solo en pruebas.
- **Lo usa:** nadie. Está en el borde de la arquitectura (una regla de ArchUnit prohíbe que algo dependa de `server`).
  La imagen Docker se construye a partir de él (`Dockerfile` de la raíz).

## Compilar, correr y probar solo este módulo

```sh
./mvnw -q -pl server -am package -DskipTests                   # solo el jar del motor
SCHED_AUTH=none SCHED_GRPC_TLS=disabled java -jar server/target/sched-server-exec.jar   # necesita un NATS con JetStream: inicio-rapido.md, camino C
./mvnw -q -pl server -am verify                                # sus pruebas (EngineEndToEndTest necesita Docker; sin él se salta)
```

Las pruebas de más peso: `EngineEndToEndTest` (motor completo con NATS real, mTLS y token),
`WarmupEndToEndTest`, `LogConfidentialityTest` (los logs no filtran tokens ni datos del estudiante) y
`IngestGrpcMutualTlsTest`.

## Herramienta de la comparación histórica con Rust

`ExportRequestCasesTest` arranca el motor y exporta a JSON solicitudes HTTP y las respuestas exactas (código,
cabeceras y cuerpo) para compararlas con otra implementación. Hoy no se porta nada a Rust; se conserva como herramienta
de esa comparación. Siempre escribe `server/target/request-cases.json`; para copiarlo a otra ruta:

```sh
./mvnw -q -pl server -am verify -DskipITs -Dsched.export.request-cases=/tmp/request-cases.json \
  -Dtest=ExportRequestCasesTest -Dsurefire.failIfNoSpecifiedTests=false
```

## Para entender más

[Spring Boot](../docs/conceptos-base.md#spring-boot) · [Actuator](../docs/conceptos-base.md#actuator) ·
[JIT y calentamiento](../docs/conceptos-base.md#jit-y-calentamiento) ·
[Prometheus y Micrometer](../docs/conceptos-base.md#prometheus-micrometer-y-métricas) ·
[Configuración completa](../docs/inicio-rapido.md#configuración) · [Observabilidad](../docs/arquitectura.md#observabilidad)
