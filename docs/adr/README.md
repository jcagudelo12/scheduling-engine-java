# Decisiones de arquitectura (ADR)

Un **ADR** (*Architecture Decision Record*) es un documento corto que registra una decisión importante de
diseño, **por qué** se tomó y qué alternativas se descartaron ([qué es y cómo escribir uno](../conceptos-base.md#adr-decisiones-de-arquitectura),
[reglas](../../CONTRIBUTING.md#decisiones-de-arquitectura-adr)). Un ADR aceptado no se reescribe: se le agregan
notas fechadas o lo reemplaza otro. Términos técnicos: [conceptos-base.md](../conceptos-base.md) y [glosario](../glosario.md).

| ADR | Decisión | Estado |
|---|---|---|
| [0000](0000-plantilla.md) | Plantilla para escribir un ADR nuevo |  |
| [0001](0001-arquitectura-hexagonal.md) | Usar arquitectura hexagonal con un módulo Maven por capa | Aceptado |
| [0002](0002-roles-de-la-institucion.md) | La institución empuja todos los datos; el motor nunca la consulta | Aceptado |
| [0003](0003-replica-del-catalogo.md) | Mantener una réplica en memoria del catálogo, alimentada por la institución | Aceptado |
| [0004](0004-contexto-del-estudiante-en-token-firmado.md) | Recibir el contexto del estudiante en un token firmado por la institución | Aceptado |
| [0005](0005-varias-instancias-con-historial-compartido.md) | Compartir el catálogo entre instancias con un historial en NATS JetStream | Aceptado |
| [0006](0006-mejores-combinaciones-con-ramificacion-y-poda.md) | Devolver las 5 mejores combinaciones con ramificación y poda y filtros del estudiante | Aceptado (implementación parcial) |
| [0007](0007-sdk-de-integracion-propio.md) | Construir el SDK de integración como un agente propio | Propuesto |
| [0008](0008-grupos-llenos-en-el-pico-de-matricula.md) | Evitar recomendar grupos que ya se llenaron durante el pico de matrícula | Propuesto |
| [0009](0009-tiempo-maximo-de-la-busqueda.md) | Acotar el tiempo de búsqueda de las 5 mejores combinaciones | Aceptado |
| [0010](0010-deteccion-de-cambios-en-el-sdk.md) | Detectar los cambios de cupo en el SDK sin cargar la base académica | Propuesto |
| [0011](0011-repartir-la-demanda-entre-grupos.md) | Evitar que el top 5 envíe a todos los estudiantes a los mismos grupos | Propuesto |

Los ADR 0008 a 0011 salen del [análisis de calidad](../calidad/README.md) (escenarios E1 a E5).

**"5 mejores" en los títulos** (0006, 0009 y 0011) es el objetivo de producto. Hoy el motor devuelve las
`SCHED_MAX_COMBINATIONS` mejores, **200 por defecto** (las pruebas de carga usan 5): ver la
[nota de implementación del ADR 0006](0006-mejores-combinaciones-con-ramificacion-y-poda.md#nota-de-implementación-2026-11-23).
