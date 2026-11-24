# adapters

**Los adaptadores: las piezas que conectan el núcleo del motor con el mundo exterior** (HTTP, gRPC, NATS).
En la [arquitectura hexagonal](../docs/conceptos-base.md#arquitectura-hexagonal-puertos-y-adaptadores) el
núcleo (`domain`, `solver`, `application`) no conoce ninguna tecnología: cada adaptador traduce entre una
tecnología concreta y los casos de uso, **sin contener reglas de negocio**. Cada subcarpeta es un módulo Maven.

| Módulo | Dirección | Qué hace | Tecnología |
|---|---|---|---|
| [`gateway-http`](gateway-http/README.md) | Entrada | API del estudiante (`POST /combinations`, `/alternatives`), sondas `/health` y `/ready`, verificación del token JWT y cliente web de demostración | Spring MVC, Jackson, BouncyCastle |
| [`grpc-catalog`](grpc-catalog/src/main/java/sched/adapters/grpc/catalog/CatalogGrpcService.java) | Entrada | `LoadCatalog`: recibe la carga completa del catálogo desde el SDK o el simulador | grpc-java |
| [`grpc-events`](grpc-events/src/main/java/sched/adapters/grpc/events/CatalogEventsGrpcService.java) | Entrada | `SyncChanges`: recibe los cambios incrementales (cupos, grupos nuevos o retirados) y responde `Ack`, `ResyncRequired` o `Rejected` | grpc-java |
| `nats-bus` | Salida | Implementa el puerto `CatalogLog` con NATS JetStream: `JetStreamCatalogLog` escribe el historial, `CatalogFollower` lo lee y lo aplica a la réplica, `NatsConnector` abre la conexión (usuario, contraseña, TLS) | jnats |
| `in-memory` | Salida | Implementa `CatalogLog` en memoria (`InMemoryCatalogLog`): lo usan el simulador y las pruebas, sin red | Java puro |

"Entrada" significa que el mundo exterior **llama** al motor; "salida", que el motor **llama** al exterior
(aquí, para guardar y leer el historial).

## De qué dependen y quién los usa

- **Dependen de:** `application` (los casos de uso y los puertos), y los de gRPC y NATS además de `proto`.
  Una regla de ArchUnit prohíbe que un adaptador dependa de otro: nunca se llaman entre sí.
- **Los usa:** `server`, que los crea y los conecta según el rol (`SCHED_ROLE`); `simulation` usa `in-memory`.

## Compilar y probar

```sh
./mvnw -q -pl adapters/gateway-http -am test      # API HTTP y filtros
./mvnw -q -pl adapters/nats-bus -am verify        # el historial contra un NATS real (necesita Docker; sin él se salta)
./mvnw -q -pl adapters/in-memory -am test         # casos de uso de punta a punta con el historial en memoria
```

Los dos adaptadores gRPC se prueban de punta a punta desde `server` (`IngestGrpcMutualTlsTest`,
`EngineEndToEndTest`) y su traducción de mensajes desde `proto` (`ConvertTest`).

## Para entender más

[gRPC](../docs/conceptos-base.md#grpc-y-protocol-buffers) ·
[Spring MVC](../docs/conceptos-base.md#spring-mvc) · [NATS y JetStream](../docs/conceptos-base.md#nats-y-jetstream) ·
[Testcontainers](../docs/conceptos-base.md#testcontainers) ·
[Contrato con la institución](../docs/contrato-institucion.md) · [API HTTP](../docs/api-http.md)
