# Scheduling Engine (Java)

Motor de **validación previa de horarios**: calcula por adelantado qué combinaciones de grupos
son compatibles para un estudiante, en lugar de rechazarlo después de que elige. Se usan las
reglas de matrícula del ITM como caso de prueba, pero el motor es agnóstico de institución.

Fuera de alcance: asignación de aulas, carga docente y planeación de la oferta académica.

## Inicio rápido

Con Docker, sin instalar Java:

```sh
docker compose -f deploy/docker-compose.yml up -d --build          # NATS, ingesta, 3 consultas, Traefik
docker compose -f deploy/docker-compose.yml run --rm simulator load # la institución simulada carga el catálogo
curl -X POST localhost:8080/combinations -H 'content-type: application/json' \
  -d '{"student_id":"est-001","eligible_courses":["MAT101","FIS101"]}'
```

Con Java 25: `./mvnw verify` y luego `java -jar simulation/target/sched-simulation.jar simulate`.

La guía completa, con los tres caminos para correrlo y la configuración, está en
[docs/inicio-rapido.md](docs/inicio-rapido.md).

## Documentación

| Documento | Para qué |
|---|---|
| [Inicio rápido](docs/inicio-rapido.md) | Correr el motor: en memoria, con Docker o en desarrollo local |
| [Decisiones de arquitectura](docs/adr/) | Por qué el motor es como es |
| [Prueba de carga](bench/README.md) | Metodología y resultados de rendimiento |

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
