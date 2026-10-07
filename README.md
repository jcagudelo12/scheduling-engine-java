# Scheduling Engine (Java)

Motor de **validación previa de horarios**: calcula por adelantado qué combinaciones de grupos
son compatibles para un estudiante, en lugar de rechazarlo después de que elige. Se usan las
reglas de matrícula del ITM como caso de prueba, pero el motor es agnóstico de institución.

Fuera de alcance: asignación de aulas, carga docente y planeación de la oferta académica.

## Cómo funciona

- **La institución empuja toda la información; el motor nunca la consulta**
  ([ADR 0002](docs/adr/0002-roles-de-la-institucion.md)).
- **Catálogo y cupos:** el SDK de la institución hace una carga completa y luego envía solo los
  cambios, numerados con `seq`. El motor mantiene una réplica en memoria
  ([ADR 0003](docs/adr/0003-replica-del-catalogo.md)).
- **Varias instancias:** una instancia de ingesta escribe en un historial de NATS JetStream y
  todas lo leen para armar su réplica ([ADR 0005](docs/adr/0005-varias-instancias-con-historial-compartido.md)).
- **Contexto del estudiante:** llegará en cada solicitud dentro de un token firmado por la
  institución ([ADR 0004](docs/adr/0004-contexto-del-estudiante-en-token-firmado.md);
  pendiente: hoy el endpoint HTTP lo recibe sin firmar).

## Estructura

Arquitectura hexagonal, con un módulo Maven por capa: las dependencias apuntan hacia adentro y
Maven impide lo contrario ([ADR 0001](docs/adr/0001-arquitectura-hexagonal.md)).

```
domain/                  sched-domain         modelo puro, sin dependencias
solver/                  sched-solver         búsqueda de combinaciones (depende de domain)
application/             sched-application    casos de uso y puertos (interfaces)
proto/                   sched-proto          contrato gRPC (src/main/proto) + conversiones
adapters/
  gateway-http/          Spring MVC: API del estudiante y sondas /health y /ready
  grpc-catalog/          grpc-java: carga completa del catálogo desde el SDK
  grpc-events/           grpc-java: cambios incrementales del catálogo
  nats-bus/              jnats: historial compartido en NATS JetStream
  in-memory/             historial en memoria (simulación y pruebas)
server/                  sched-server         Spring Boot: composición e inyección
simulation/              sched-simulation     simulador de escenarios e institución simulada
benchmarks/              sched-benchmarks     microbenchmarks con JMH
bench/                   prueba de carga con k6
deploy/                  despliegue local con varias instancias
docs/adr/                decisiones de arquitectura
```

## Tecnologías

| Tecnología | Para qué se usa |
|---|---|
| Java 25 (LTS) | Lenguaje; hilos virtuales para atender muchas solicitudes a la vez |
| Maven (con wrapper) | Compilación, pruebas y un módulo por capa |
| Spring Boot 4.1 (Spring MVC) | API HTTP del estudiante y composición de la aplicación |
| grpc-java + Protocol Buffers | Contrato con la institución; la institución genera su SDK desde los `.proto` |
| jnats (NATS JetStream) | Historial compartido del catálogo entre instancias |
| JUnit 5 y JMH | Pruebas y microbenchmarks |
| Docker, Docker Compose y Traefik | Imagen única, despliegue con varias instancias y balanceo |
| k6 | Prueba de carga |
| GitHub Actions | Integración continua (`./mvnw verify`) |

## Comandos

Requiere Java 25. Maven se descarga solo con el wrapper (`mvnw.cmd` en Windows).

```sh
./mvnw verify                                              # compila y corre las pruebas
java -jar server/target/sched-server.jar                   # motor (HTTP :8080, gRPC :50051, necesita NATS)
java -jar simulation/target/sched-simulation.jar simulate  # escenario de ejemplo en memoria
java -jar benchmarks/target/benchmarks.jar                 # microbenchmarks
```

### Simulador e institución simulada

```sh
java -jar simulation/target/sched-simulation.jar simulate [escenario.json]   # en memoria, sin red
java -jar simulation/target/sched-simulation.jar load [escenario.json] [seq] # carga el catálogo por gRPC
java -jar simulation/target/sched-simulation.jar seats <seq> <grupo> <cupos> # envía un cambio de cupo
```

`load` y `seats` usan el contrato gRPC real, como lo haría el SDK de la institución.
`SCHED_INGEST_URL` indica la ingesta (por defecto `localhost:50051`). El escenario de ejemplo
está en [`simulation/fixtures/sample.json`](simulation/fixtures/sample.json).

## Despliegue local con varias instancias

```sh
docker compose -f deploy/docker-compose.yml up -d --build      # NATS, ingesta, 3 consultas, Traefik
java -jar simulation/target/sched-simulation.jar load           # carga el catálogo
curl -X POST localhost:8080/combinations -H 'content-type: application/json' \
  -d '{"student_id":"est-001","eligible_courses":["MAT101","FIS101"]}'
docker compose -f deploy/docker-compose.yml up -d --scale engine=5   # más réplicas; Traefik las toma solo
```

| Servicio | Rol (`SCHED_ROLE`) | Puertos |
|---|---|---|
| `ingest` | `ingest`: recibe datos de la institución (una sola instancia) | gRPC `:50051` |
| `engine` | `query`: atiende estudiantes, escalable | solo red interna |
| `lb` | Traefik frente a `engine`; solo enruta a réplicas `healthy` | HTTP `:8080`, panel `:8090` |
| `nats` | JetStream con el historial | `:4222`, monitoreo `:8222` |

## Configuración

Variables de entorno (ver [`application.properties`](server/src/main/resources/application.properties)):

| Variable | Por defecto | Qué hace |
|---|---|---|
| `SCHED_ROLE` | `all` | `ingest`, `query` o `all` ([ADR 0005](docs/adr/0005-varias-instancias-con-historial-compartido.md)) |
| `SCHED_HTTP_PORT` | `8080` | Puerto HTTP |
| `SCHED_GRPC_PORT` | `50051` | Puerto gRPC de ingesta |
| `SCHED_NATS_URL` | `nats://localhost:4222` | Servidor NATS |
| `SCHED_NATS_STREAM` | `SCHED_CATALOG` | Stream del historial |
| `SCHED_NATS_SUBJECT_PREFIX` | `sched` | Prefijo de los sujetos de NATS |
| `SCHED_MAX_COMBINATIONS` | `200` | Combinaciones máximas por respuesta |

## Rendimiento

La prueba de carga controlada y sus resultados están en [`bench/README.md`](bench/README.md).

## Origen

Este proyecto nació como la versión en Java de una comparación de rendimiento Rust vs Java
del mismo motor (repositorio [`Scheduling_Engine`](https://github.com/jcagudelo12/Scheduling_Engine)),
con la misma arquitectura, el mismo contrato y las mismas pruebas. El historial de git de este
repo conserva los commits originales de la carpeta `java/`.

## Pendiente

- Token firmado con el contexto del estudiante ([ADR 0004](docs/adr/0004-contexto-del-estudiante-en-token-firmado.md)).
- Las 5 mejores combinaciones con ramificación y poda y los filtros del estudiante
  ([ADR 0006](docs/adr/0006-mejores-combinaciones-con-ramificacion-y-poda.md)).
- Avisos de cupo en vivo por WebSocket.
- SDK de la institución (agente con consultas configurables) y conector de referencia.
