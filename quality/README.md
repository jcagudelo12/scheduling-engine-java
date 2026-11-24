# quality

**Un módulo sin código de producción: vigila la arquitectura y reúne la cobertura de todos los módulos.** Solo
tiene pruebas y configuración de JaCoCo.

## Qué hay dentro

| Archivo | Qué hace |
|---|---|
| `src/test/java/sched/architecture/ArchitectureTest.java` | Reglas de [ArchUnit](../docs/conceptos-base.md#archunit): `domain` solo usa `java.*`; `solver` solo `domain`; `application` no conoce adaptadores, `server`, Spring, gRPC, protobuf, NATS, Jackson ni Micrometer; los adaptadores no dependen entre sí; nada depende de `server`, `simulation` ni `benchmarks`; no hay ciclos entre paquetes |
| `pom.xml` | Declara como dependencia (solo para leer sus clases) a todos los módulos de producción y agrega el reporte de [JaCoCo](../docs/conceptos-base.md#jacoco-y-la-cobertura) |

## De qué depende y quién lo usa

- **Depende de:** todos los módulos de producción (para analizar sus clases y sumar su cobertura), ArchUnit y JUnit.
- **Lo usa:** nadie. Corre dentro de `./mvnw verify`, así que **una dependencia indebida rompe la compilación aunque el código compile**.

## Compilar y probar solo este módulo

```sh
./mvnw -q -pl quality -am verify
```

Esto corre las pruebas de todos los módulos de los que depende y deja el **reporte agregado de cobertura** en
`quality/target/site/jacoco-aggregate/index.html` (ábrelo en un navegador). La CI lo publica como artefacto `jacoco`.

## Para entender más

[Arquitectura hexagonal](../docs/conceptos-base.md#arquitectura-hexagonal-puertos-y-adaptadores) ·
[Regla de capas](../CONTRIBUTING.md#arquitectura-la-regla-que-no-se-rompe) ·
[Cobertura y umbrales](../CONTRIBUTING.md#cobertura) · [ADR 0001](../docs/adr/0001-arquitectura-hexagonal.md)
