# application

**Los casos de uso del motor y los puertos (interfaces) que necesita del exterior.** Es la capa que dice
*qué hace* el motor: generar combinaciones, sugerir alternativas, ingerir el catálogo y mantener la réplica.
No sabe de HTTP, protobuf ni NATS: los adaptadores la llaman, y ella llama a los adaptadores a través de
puertos.

## Qué hay dentro

Paquete `sched.application` (`src/main/java/sched/application/`):

| Clase | Cuándo se ejecuta | Qué hace |
|---|---|---|
| `GenerateCombinations` | `POST /combinations` | Lee la réplica y llama al solver; verifica el tope de créditos y aplica `avoid` como costo |
| `SuggestAlternatives` | `POST /alternatives` | Con la selección actual, propone combinaciones que cambian lo mínimo (distancia y luego penalización) |
| `IngestCatalog` | `LoadCatalog` (gRPC) | Escribe la carga completa en el historial |
| `IngestCatalogEvent` | `SyncChanges` (gRPC) | Valida el `seq` con `IngestState` y escribe el cambio |
| `ApplyLogEntry` | Por cada entrada del historial | Aplica la entrada a la réplica local |
| `CatalogReplica` | siempre | La réplica en memoria, con lectura concurrente y la bandera `stale` |
| `IngestState`, `Readiness` | siempre | Último `seq` aceptado (solo ingesta) y si la instancia está lista (incluye el calentamiento) |
| `Combinations`, `Alternatives`, `ScoredSchedule`, `CreditCheck`, `AppException`, `PortException` | | Resultados y errores de los casos de uso |

**Puertos** (`sched.application.ports`):

- `CatalogLog`: el historial compartido del catálogo. Lo implementan `adapters/nats-bus` (NATS JetStream) y `adapters/in-memory`.
- `EngineMetrics`: lo que el núcleo reporta sobre su propio trabajo. Por defecto no hace nada
  (`EngineMetrics.NOOP`); `server` lo implementa con Micrometer.

## De qué depende y quién lo usa

- **Depende de:** `domain`, `solver` y `slf4j-api` (solo la interfaz de logs). ArchUnit prohíbe que conozca
  adaptadores, Spring, gRPC, protobuf, NATS, Jackson y Micrometer.
- **Lo usan:** todos los `adapters/*` (para llamar a los casos de uso), `server`, `simulation`.

## Compilar y probar solo este módulo

```sh
./mvnw -q -pl application -am verify     # incluye el umbral de cobertura (90 % de líneas, 75 % de ramas)
```

## Para entender más

[Arquitectura hexagonal](../docs/conceptos-base.md#arquitectura-hexagonal-puertos-y-adaptadores) ·
[Qué clase hace qué](../docs/arquitectura.md#qué-clase-hace-qué) ·
[ADR 0001](../docs/adr/0001-arquitectura-hexagonal.md) · [ADR 0005](../docs/adr/0005-varias-instancias-con-historial-compartido.md)
