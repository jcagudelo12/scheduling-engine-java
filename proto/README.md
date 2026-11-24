# proto

**El contrato gRPC con la institución, escrito en Protocol Buffers, y las conversiones entre esos mensajes
y el dominio.** Los `.proto` son un **contrato público**: el SDK y las universidades dependen de ellos.

## Qué hay dentro

| Ruta | Qué es |
|---|---|
| `src/main/proto/institution/v1/catalog.proto` | Servicio `CatalogService`: `LoadCatalog`, la carga completa del catálogo en un stream de bloques |
| `src/main/proto/institution/v1/catalog_events.proto` | Servicio `CatalogEventsService`: `SyncChanges`, el stream bidireccional de cambios; respuestas `Ack`, `ResyncRequired` y `Rejected` |
| `src/main/proto/institution/v1/common.proto` | Mensajes compartidos (grupo, franja, curso…) |
| `src/main/proto/engine/v1/catalog_log.proto` | **Interno:** el formato de las entradas del historial en NATS. No es público |
| `src/main/java/sched/proto/Convert.java` | Traduce entre mensajes protobuf y objetos del dominio, validando los datos |

Las clases Java de los mensajes y de los servicios **se generan al compilar** (plugin `protobuf-maven-plugin`)
y quedan en `proto/target/generated-sources/`; no se editan ni se versionan.

## Reglas para cambiar el contrato

- Cambios **compatibles** (se pueden hacer en `v1`): campos nuevos con números nuevos, mensajes y RPC nuevos.
- Cambios **incompatibles** (exigen `v2`): renombrar o renumerar campos, cambiar tipos o quitarlos. Nunca reutilices el número de un campo borrado: márcalo `reserved`.
- Actualiza [contrato-institucion.md](../docs/contrato-institucion.md) en el mismo pull request.

Detalle en [CONTRIBUTING.md](../CONTRIBUTING.md#contrato-grpc).

## De qué depende y quién lo usa

- **Depende de:** `domain`, `protobuf-java` y `grpc-stub`/`grpc-protobuf`.
- **Lo usan:** `adapters/grpc-catalog`, `adapters/grpc-events`, `adapters/nats-bus` (para escribir el historial) y `simulation` (el cliente de la institución simulada).

## Compilar y probar solo este módulo

```sh
./mvnw -q -pl proto -am test        # genera las clases y corre ConvertTest
```

## Para entender más

[gRPC y Protocol Buffers](../docs/conceptos-base.md#grpc-y-protocol-buffers) ·
[Contrato con la institución](../docs/contrato-institucion.md) · [ADR 0007](../docs/adr/0007-sdk-de-integracion-propio.md)
