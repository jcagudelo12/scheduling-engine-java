# benchmarks

**Microbenchmarks con JMH: miden cuánto tarda el cálculo del solver, el catálogo y la verificación de tokens, sin
red ni HTTP de por medio.** No es la prueba de carga (esa está en [`bench/`](../bench/README.md)).

## Qué hay dentro

Paquete `sched.benchmarks` (`src/main/java/sched/benchmarks/`):

| Clase | Qué mide |
|---|---|
| `SolverComparisonBenchmark` | `forward-checking` contra `backtracking` en un caso de 6 cursos × 5 grupos, en un peor caso sin solución (7 × 8) y en uno tipo matrícula, con y sin costo |
| `BacktrackingBenchmark` | El solver base en 6 cursos × 5 grupos |
| `CatalogLookupBenchmark` | Buscar los grupos de 6 cursos en un catálogo de 5.000 |
| `JwtVerifyBenchmark` | Cuánto cuesta verificar la firma Ed25519 de un token |
| `ExportSolverCases` | **No es un benchmark:** herramienta de la comparación histórica con Rust (ver abajo) |

## De qué depende y quién lo usa

- **Depende de:** `domain`, `solver`, `adapters/gateway-http` (para el verificador de tokens), BouncyCastle y JMH.
- **Lo usa:** nadie del motor; lo corre una persona o `bench/micro.sh`.

## Compilar y correr

```sh
./mvnw -q -pl benchmarks -am package -DskipTests
java -jar benchmarks/target/benchmarks.jar SolverComparisonBenchmark     # una sola clase; tarda unos minutos
java -jar benchmarks/target/benchmarks.jar -l                            # lista los benchmarks disponibles
bench/micro.sh                                                           # todos, y deja bench/results/jmh.json
```

Como en cualquier medición, el resultado depende del equipo: compara corridas de **tu** máquina entre sí.

## Herramienta de la comparación histórica con Rust

`ExportSolverCases` genera un JSON determinista (semillas fijas) con problemas del solver y la salida **exacta** de ambos
solvers: los de `SolverComparisonBenchmark` y 600 aleatorios. Se escribió para que la versión en Rust los verificara. Hoy no se porta
nada a Rust (el repositorio en Rust es solo antecedente); se conserva como herramienta de esa comparación y como forma de
fijar el comportamiento del solver:

```sh
./mvnw -q -pl benchmarks -am package -DskipTests
java -cp benchmarks/target/benchmarks.jar sched.benchmarks.ExportSolverCases /tmp/solver-cases.json
```

## Para entender más

[JMH](../docs/conceptos-base.md#jmh) · [JIT y calentamiento](../docs/conceptos-base.md#jit-y-calentamiento) ·
[El solver](../docs/conceptos-base.md#el-problema-del-horario-y-el-solver) · [Prueba de carga](../bench/README.md)
