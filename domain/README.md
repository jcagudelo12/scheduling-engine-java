# domain

**El modelo del problema: catálogo, grupos, franjas y estudiante, en Java puro.** Es el centro de la
[arquitectura hexagonal](../docs/conceptos-base.md#arquitectura-hexagonal-puertos-y-adaptadores): no sabe de
HTTP, gRPC, NATS ni de ningún framework.

## Qué hay dentro

Paquete `sched.domain` (`src/main/java/sched/domain/`):

| Clases | Qué representan |
|---|---|
| `Catalog` | El catálogo en memoria: los grupos del periodo. `apply` aplica un cambio respetando el orden de `seq`; `openSectionsOf` devuelve los grupos con cupo de unos cursos |
| `Section`, `SectionId`, `SectionSeats`, `Course`, `CourseId` | Un grupo (oferta concreta de un curso, con horario y cupo), un curso y sus identificadores |
| `TimeSlot`, `Weekday` | Una franja semanal `[inicio, fin)` en minutos y el día de la semana; saben si se cruzan con otra |
| `Schedule` | Una combinación: un grupo por curso, sin cruces |
| `StudentContext`, `StudentId`, `Preferences` | Lo que se sabe del estudiante en una solicitud: id, cursos habilitados y ventanas `avoid` |
| `EnrollmentRules` | Reglas del periodo definidas por la institución (tope de créditos) |
| `CatalogEvent`, `CatalogChange`, `ApplyOutcome`, `SequenceGapException` | Los cambios incrementales (`seats_changed`, `section_upserted`, `section_removed`), su resultado y el error de hueco de `seq` |

Los objetos son **inmutables** (`record`). Los términos están en el [glosario](../docs/glosario.md#dominio).

## De qué depende y quién lo usa

- **Depende de:** nada, solo del JDK. Una regla de ArchUnit exige que `domain` use únicamente `java.*`
  ([`ArchitectureTest`](../quality/src/test/java/sched/architecture/ArchitectureTest.java)).
- **Lo usan:** `solver`, `application`, `proto` (para convertir mensajes) y `benchmarks`; por tanto, todo lo demás.

## Compilar y probar solo este módulo

```sh
./mvnw -q -pl domain test
```

Tiene un umbral de cobertura mínimo (90 % de líneas, 75 % de ramas) que hace fallar `verify` si baja
([cómo funciona](../CONTRIBUTING.md#cobertura)).

## Para entender más

[Java y JDK](../docs/conceptos-base.md#java-jdk-y-jvm) · [JUnit 5](../docs/conceptos-base.md#junit-5) ·
[JaCoCo](../docs/conceptos-base.md#jacoco-y-la-cobertura) · [ADR 0003: réplica del catálogo](../docs/adr/0003-replica-del-catalogo.md)
