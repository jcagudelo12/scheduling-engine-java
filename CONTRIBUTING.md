# Cómo contribuir

> **Términos técnicos.** Maven, gRPC, NATS, ArchUnit, JaCoCo, Testcontainers, ADR y Conventional Commits están explicados desde cero en [docs/conceptos-base.md](docs/conceptos-base.md); el vocabulario del dominio, en el [glosario](docs/glosario.md).

Convenciones para trabajar en el motor. Para correrlo, empieza por
[docs/inicio-rapido.md](docs/inicio-rapido.md); para entender cómo está armado,
[docs/arquitectura.md](docs/arquitectura.md).

## Flujo de trabajo

1. Crea una rama desde `master`: `git switch -c feat/filtro-jornada`.
2. Haz commits pequeños, cada uno con un solo propósito (ver [Commits](#commits)).
3. Antes de subir, corre `./mvnw verify`. Es lo mismo que corre la CI.
4. Abre un pull request hacia `master`. La CI ([`.github/workflows/ci.yml`](.github/workflows/ci.yml))
   tiene que pasar antes de integrarlo.

## Arquitectura: la regla que no se rompe

Las dependencias apuntan **hacia adentro** ([ADR 0001](docs/adr/0001-arquitectura-hexagonal.md)):

```
adapters/*, server  →  application  →  solver  →  domain
```

- `domain` y `solver` no dependen de ningún framework: nada de Spring, gRPC, NATS ni Jackson.
- `application` define los puertos (interfaces) y los casos de uso. No sabe de HTTP ni de
  protobuf.
- Los adaptadores traducen entre el mundo exterior y los casos de uso. No contienen reglas de
  negocio.
- `server` es el único que conoce a todos y los conecta según `SCHED_ROLE`.

Maven lo hace cumplir: si un módulo no declara la dependencia, no compila. Y las reglas de
ArchUnit de [`quality`](quality/src/test/java/sched/architecture/ArchitectureTest.java) lo
vigilan en cada `verify` aunque el código compile: `domain` solo depende de `java.*`, `solver`
solo de `domain`, `application` no conoce adaptadores, Spring, gRPC, NATS, Jackson ni Micrometer,
los adaptadores no dependen entre sí, nada depende de `server` ni de `simulation`, y no hay ciclos
entre paquetes de primer nivel. Si sientes que necesitas importar algo "hacia afuera",
probablemente falta un puerto en `application` (así se midió el motor: puerto `EngineMetrics`).

[docs/arquitectura.md](docs/arquitectura.md#dónde-agregar-cosas) indica dónde va cada tipo de
cambio.

## Comandos útiles

```sh
./mvnw verify                                   # todo: compila y corre todas las pruebas
./mvnw -q -pl solver -am test                   # pruebas de un módulo (y lo que necesita)
./mvnw -q -pl solver -am test -Dtest=BacktrackingSolverTest -Dsurefire.failIfNoSpecifiedTests=false
./mvnw -q -pl server -am package -DskipTests    # solo el jar del motor (server/target/sched-server-exec.jar)
./mvnw -q -pl quality -am verify                # reglas de arquitectura y reporte agregado de cobertura
./mvnw -q -pl simulation -am package -DskipTests
./mvnw -q -pl adapters/gateway-http -Pjs-tests test   # lógica del cliente web con `node --test` (opcional, Node 22.7+)
java -jar simulation/target/sched-simulation.jar simulate   # ver el resultado de un escenario sin red (usa BacktrackingSolver, no el solver por defecto)
```

`-pl` elige el módulo y `-am` compila también los módulos de los que depende. Al filtrar una
prueba con `-Dtest`, `-Dsurefire.failIfNoSpecifiedTests=false` evita que fallen los módulos
donde esa prueba no existe.

Para medir rendimiento: microbenchmarks con `bench/micro.sh` y prueba de carga con
`bench/run.sh` ([bench/README.md](bench/README.md)).

## Pruebas

| Dónde | Qué se prueba | Ejemplo |
|---|---|---|
| `domain/src/test` | Reglas del modelo, sin dependencias | [`CatalogTest`](domain/src/test/java/sched/domain/CatalogTest.java) |
| `solver/src/test` | La búsqueda de combinaciones | [`BacktrackingSolverTest`](solver/src/test/java/sched/solver/BacktrackingSolverTest.java) |
| `adapters/in-memory/src/test` | Casos de uso de punta a punta con el historial en memoria | [`UseCasesTest`](adapters/in-memory/src/test/java/sched/adapters/inmemory/UseCasesTest.java) |
| `proto/src/test` | Conversión entre protobuf y dominio | [`ConvertTest`](proto/src/test/java/sched/proto/ConvertTest.java) |
| `simulation/src/test` | Escenarios completos | [`SimulationTest`](simulation/src/test/java/sched/simulation/SimulationTest.java) |
| `quality/src/test` | Reglas de arquitectura (ArchUnit) sobre todos los módulos | [`ArchitectureTest`](quality/src/test/java/sched/architecture/ArchitectureTest.java) |
| `adapters/nats-bus/src/test` | El historial contra un NATS JetStream real (Testcontainers) | [`JetStreamIntegrationTest`](adapters/nats-bus/src/test/java/sched/adapters/nats/JetStreamIntegrationTest.java) |
| `server/src/test` | El motor completo con Spring Boot, NATS real, mTLS y token; métricas y logs | [`EngineEndToEndTest`](server/src/test/java/sched/server/EngineEndToEndTest.java) |

- Todo cambio de comportamiento lleva su prueba en el módulo más interno posible.
- Las pruebas usan JUnit 5. Solo las de integración (`JetStreamIntegrationTest` y
  `EngineEndToEndTest`) necesitan **Docker**, para levantar `nats:2` con Testcontainers; sin Docker
  se saltan limpiamente (`@Testcontainers(disabledWithoutDocker = true)`) y el resto pasa. Con
  Docker disponible, `./mvnw verify` las corre; en la CI siempre corren. La imagen `nats:2` se
  descarga la primera vez.
- Si una prueba nueva tarda más de unos 30 s, no la dejes en `verify`: muévela a un perfil Maven
  `integration`. Hoy no hace falta: la más lenta tarda unos 10 s.
- Un `@Testcontainers` en una clase nueva debe llevar `disabledWithoutDocker = true`.
- Las pruebas de logs capturan el log (Logback) y comprueban que no se filtran tokens, cuerpos ni
  ids de estudiante ([política](docs/arquitectura.md#observabilidad)). Un log nuevo no debe incluir
  datos de la solicitud.

### Cobertura

`verify` mide la cobertura con JaCoCo en cada módulo (`target/site/jacoco/index.html`) y produce un
reporte agregado en `quality/target/site/jacoco-aggregate/index.html` (la CI lo publica como artefacto
`jacoco`). El código generado de protobuf no cuenta.

`domain`, `solver` y `application` (el núcleo) tienen un **umbral mínimo** que hace fallar `verify`
si la cobertura baja. Se fija en el `pom.xml` de cada módulo (`jacoco.line.min`, `jacoco.branch.min`;
en el pom raíz valen 0, es decir, sin exigencia):

| Módulo | Líneas (mínimo) | Ramas (mínimo) | Medido al fijarlo |
|---|---|---|---|
| `domain` | 90 % | 75 % | 96 % líneas, 85 % ramas |
| `solver` | 93 % | 90 % | 98 % líneas, 96 % ramas |
| `application` | 90 % | 75 % | 95 % líneas, 81 % ramas |

Los umbrales dejan unos puntos de margen; no se bajan para hacer pasar un cambio: se agrega la
prueba que falta. Si un cambio sube la cobertura de forma sostenida, súbalos. Para ver qué línea
falta, abra el reporte del módulo (`./mvnw -q -pl application -am verify`).
- Para una regla nueva del horario, una prueba en `domain` o `solver` es más rápida y clara que
  una de punta a punta.

## Estilo de código

- **Identificadores en inglés** (`Catalog`, `openSectionsOf`); **comentarios, logs, mensajes de
  error y documentación en español**.
- Comentarios para explicar el *porqué*, no el *qué*. Las clases públicas llevan un Javadoc
  corto con su propósito.
- Java moderno: `record` para datos, `sealed` y `switch` con patrones, hilos virtuales.
- Valores inmutables en el dominio.

## Commits

Formato [Conventional Commits](https://www.conventionalcommits.org/), en inglés, como el resto
del historial:

```
<tipo>(<alcance opcional>): <resumen en imperativo>

<cuerpo opcional: el porqué del cambio>
```

| Tipo | Para |
|---|---|
| `feat` | Funcionalidad nueva |
| `fix` | Corrección de un error |
| `refactor` | Cambio interno sin cambio de comportamiento |
| `test` | Solo pruebas |
| `docs` | Solo documentación |
| `build` | Maven, Docker, dependencias |
| `ci` | GitHub Actions |
| `chore` | Mantenimiento menor |

Alcances habituales: `server`, `solver`, `simulation`, `deploy`, `bench`, `proto`, `docker`,
`adr`.

Ejemplos del historial:

```
feat(server): enable gRPC server reflection on the ingest port
build(docker): declare native access for Netty to silence JVM warnings
docs(adr): add architecture decision records
```

## Decisiones de arquitectura (ADR)

Escribe un ADR en [`docs/adr/`](docs/adr/) cuando una decisión:

- cambia la forma del sistema (un componente, un protocolo, una tecnología nueva);
- es difícil de revertir;
- o alguien preguntará dentro de seis meses "¿por qué se hizo así?".

Cómo hacerlo:

1. Copia [`0000-plantilla.md`](docs/adr/0000-plantilla.md) con el siguiente número y un título
   corto en imperativo.
2. Empieza con estado **Propuesto** y discútelo en el pull request.
3. Al aprobarlo, pasa a **Aceptado**. Un ADR aceptado no se reescribe: si la decisión cambia, se
   crea uno nuevo que lo reemplaza y se actualiza el estado del anterior.
4. Lo que sí se permite en un ADR aceptado son **notas fechadas** al final (`## Nota de
   implementación (AAAA-MM-DD)` o `## Nota de revisión (AAAA-MM-DD)`) que cuentan qué parte ya se
   hizo o qué dato nuevo matiza la decisión, sin borrar ni cambiar el texto original. Ejemplos:
   [ADR 0004](docs/adr/0004-contexto-del-estudiante-en-token-firmado.md#nota-de-implementación-2026-11-22) y
   [ADR 0006](docs/adr/0006-mejores-combinaciones-con-ramificacion-y-poda.md#nota-de-implementación-2026-11-23).
   Solo el estado (la línea `Estado:`) se actualiza en el texto original.
   Si lo decidido cambia de verdad, el camino sigue siendo un ADR nuevo.

## Contrato gRPC

Los `.proto` de [`proto/src/main/proto/institution/v1/`](proto/src/main/proto/institution/v1/)
son un contrato público: el SDK y las universidades dependen de él.

- **Cambios compatibles** (se pueden hacer en `v1`): agregar campos nuevos con números nuevos,
  mensajes nuevos o RPC nuevos.
- **Cambios incompatibles** (requieren `v2`): renombrar o renumerar campos, cambiar tipos o
  quitar campos. Nunca reutilices el número de un campo borrado; márcalo como `reserved`.
- Actualiza [docs/contrato-institucion.md](docs/contrato-institucion.md) en el mismo pull
  request.

## Documentación

La documentación vive en el repositorio y se actualiza **en el mismo pull request** que el
código que describe:

| Si cambias… | Actualiza… |
|---|---|
| Comandos, puertos o variables de entorno | [docs/inicio-rapido.md](docs/inicio-rapido.md) |
| La API HTTP | [docs/api-http.md](docs/api-http.md) |
| El contrato gRPC | [docs/contrato-institucion.md](docs/contrato-institucion.md) |
| Módulos o flujo de datos | [docs/arquitectura.md](docs/arquitectura.md) |
| El formato de escenarios o el simulador | [docs/simulador.md](docs/simulador.md) |
| Un término nuevo del dominio | [docs/glosario.md](docs/glosario.md) |
| Una tecnología nueva | [docs/conceptos-base.md](docs/conceptos-base.md) (una sección con qué es, para qué se usa, dónde está y cómo verla) y la tabla de tecnologías del [README](README.md#tecnologías) |
| Qué contiene, de qué depende o cómo se prueba un módulo | El `README.md` de ese módulo |
| Una decisión de arquitectura | Un [ADR](docs/adr/README.md) nuevo |
| Una cifra de rendimiento | [bench/README.md](bench/README.md#en-qué-condiciones-se-midió-cada-cifra) con las condiciones de la medición (token, calentamiento, catálogo, fecha ISO) |
