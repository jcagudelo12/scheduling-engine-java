# solver

**El algoritmo que busca las combinaciones de grupos sin cruces de horario.** Recibe un problema ya
filtrado por cupo (cursos a cubrir y grupos candidatos) y devuelve las mejores combinaciones. No sabe de
frameworks ni de dónde vienen los datos.

## Qué hay dentro

Paquete `sched.solver` (`src/main/java/sched/solver/`):

| Clase | Qué hace |
|---|---|
| `Solver` | La interfaz común de los dos algoritmos |
| `ForwardCheckingSolver` | **El solver por defecto** (`SCHED_SOLVER=forward-checking`). Búsqueda con retroceso, *forward checking* y MRV; con costo, ramificación y poda para las *k* mejores. Detecta los cruces con vectores de bits |
| `BacktrackingSolver` | La **línea base** (`SCHED_SOLVER=backtracking`): enumeración con retroceso y poda por cruce par a par. Sirve para comparar y para validar al otro. El comando `simulate` del simulador usa siempre este |
| `Problem`, `SectionCost` | La entrada: cursos, grupos candidatos, cuántos resultados, un costo opcional por grupo y un tiempo máximo |
| `SearchResult`, `Scored` | La salida: las combinaciones (con su costo) y si la búsqueda **terminó** o se cortó por el tiempo |
| `SlotBitmaps`, `Budget` | Ayudas: la codificación de franjas en bits y el reloj de la búsqueda |

Los algoritmos están explicados con un ejemplo pequeño en
[conceptos-base](../docs/conceptos-base.md#el-problema-del-horario-y-el-solver) y en
[arquitectura](../docs/arquitectura.md#qué-clase-hace-qué).

## De qué depende y quién lo usa

- **Depende de:** `domain`. ArchUnit exige que no use nada más (ni Spring, ni gRPC, ni Jackson).
- **Lo usan:** `application` (los casos de uso lo llaman a través de la interfaz), `benchmarks`, `simulation` y
  `server` (elige el solver según `SCHED_SOLVER`).

## Compilar y probar solo este módulo

```sh
./mvnw -q -pl solver -am test                                     # -am también compila domain
./mvnw -q -pl solver -am test -Dtest=ForwardCheckingSolverPropertiesTest -Dsurefire.failIfNoSpecifiedTests=false
```

Incluye [pruebas basadas en propiedades (jqwik)](../docs/conceptos-base.md#pruebas-basadas-en-propiedades-jqwik)
que comparan los dos solvers con una enumeración exhaustiva. Umbral de cobertura: 93 % de líneas y 90 % de ramas.

Para medir los solvers: `java -jar benchmarks/target/benchmarks.jar SolverComparisonBenchmark`
([benchmarks](../benchmarks/README.md)).

## Para entender más

[CSP, backtracking, forward checking, MRV y ramificación y poda](../docs/conceptos-base.md#el-problema-del-horario-y-el-solver) ·
[ADR 0006](../docs/adr/0006-mejores-combinaciones-con-ramificacion-y-poda.md) ·
[ADR 0009: tiempo máximo de búsqueda](../docs/adr/0009-tiempo-maximo-de-la-busqueda.md)
