# Cómo contribuir

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

Maven lo hace cumplir: si un módulo no declara la dependencia, no compila. Si sientes que
necesitas importar algo "hacia afuera", probablemente falta un puerto en `application`.

[docs/arquitectura.md](docs/arquitectura.md#dónde-agregar-cosas) indica dónde va cada tipo de
cambio.

## Comandos útiles

```sh
./mvnw verify                                   # todo: compila y corre todas las pruebas
./mvnw -q -pl solver -am test                   # pruebas de un módulo (y lo que necesita)
./mvnw -q -pl solver -am test -Dtest=BacktrackingSolverTest -Dsurefire.failIfNoSpecifiedTests=false
./mvnw -q -pl server -am package -DskipTests    # solo el jar del motor
./mvnw -q -pl simulation -am package -DskipTests
java -jar simulation/target/sched-simulation.jar simulate   # probar un cambio del solver sin red
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

- Todo cambio de comportamiento lleva su prueba en el módulo más interno posible.
- Las pruebas usan JUnit 5 y no necesitan NATS ni Docker.
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
