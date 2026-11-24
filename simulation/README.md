# simulation

**El simulador: una institución simulada, un simulador de escenarios y un experimento estadístico, todo en un
solo jar.** Sirve para probar el motor sin una universidad real, para cargarle datos y provocar cambios, y para
medir con estadística si la matrícula asistida por el motor ahorra tiempo frente a la actual.

## Qué hay dentro

Paquete `sched.simulation` (`src/main/java/sched/simulation/`):

| Clases o paquetes | Qué hacen |
|---|---|
| `Main` | Punto de entrada: reparte los comandos (`simulate`, `load`, `seats`, `seat-storm`, `keygen`, `token`, `demo-catalog`, `experiment`, `analyze`, `calibrate`, `sensitivity`, `calibration`). Sin argumentos imprime la ayuda |
| `Simulation`, `Scenario` | `simulate`: corre un escenario JSON en memoria con los casos de uso reales y el solver `BacktrackingSolver` |
| `FakeInstitution` | Cliente gRPC que hace el papel del SDK: `load` (carga completa) y `seats` (cambios de cupo) contra un motor real |
| `SeatStorm` | `seat-storm`: la ráfaga de cupos que vacía grupos a ritmo fijo (escenario E1) |
| `DevAuth` | `keygen` y `token`: pares de llaves Ed25519 y tokens firmados **de desarrollo** |
| `experiment/` | El experimento pareado: oferta y perfiles sintéticos, plataforma simulada, estudiantes reactivo (A) y asistido (B) |
| `analysis/` | El análisis estadístico (`analyze`): Shapiro-Wilk, t, Wilcoxon, bootstrap, tamaño del efecto y potencia |
| `calibration/`, `sensitivity/` | `calibrate` (ajusta el flujo A a la consulta real) y `sensitivity` (qué tan robusta es la conclusión) |
| `fixtures/sample.json` | El escenario de ejemplo |
| `experiments/` | Configuraciones y resultados del experimento ([cómo reproducirlo](experiments/README.md)) |

Todos los comandos y el formato del escenario: [docs/simulador.md](../docs/simulador.md).

## De qué depende y quién lo usa

- **Depende de:** `application`, `solver`, `proto`, `adapters/in-memory` (el historial), gRPC Netty, Jackson y Apache Commons Statistics.
- **Lo usa:** nadie del motor (ArchUnit prohíbe que algo dependa de `simulation`). Lo usan las personas, el `Dockerfile`
  (imagen `simulator`, servicio `simulator` del Compose) y los scripts de `bench/`.

## Compilar, correr y probar solo este módulo

```sh
./mvnw -q -pl simulation -am package -DskipTests                                        # genera simulation/target/sched-simulation.jar
java -jar simulation/target/sched-simulation.jar simulate simulation/fixtures/sample.json   # sin red
java -jar simulation/target/sched-simulation.jar                                        # ayuda con todos los comandos
./mvnw -q -pl simulation -am test                                                       # pruebas
```

`simulate` usa **siempre `BacktrackingSolver`**, no el `forward-checking` del motor: sirve para ver el resultado de
un escenario, no para medir el rendimiento real.

## Para entender más

[Estadística del simulador, a nivel intuitivo](../docs/conceptos-base.md#estadística-del-simulador-a-nivel-intuitivo) ·
[gRPC](../docs/conceptos-base.md#grpc-y-protocol-buffers) · [JWT](../docs/conceptos-base.md#jwt-y-bearer) ·
[Glosario de simulación](../docs/glosario.md#simulación-y-estadística) · [Contrato con la institución](../docs/contrato-institucion.md)
