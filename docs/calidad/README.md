# Atributos de calidad

Análisis de calidad del motor: qué calidad necesita, dónde están las tensiones y qué decisiones
quedan por tomar. Corresponde a la entrega del Bloque 2 del curso de arquitectura, sobre el
proyecto propio.

Se omite lo que no genera tensión: autorización, roles, facilidad de aprendizaje y el
rendimiento con carga normal, que ya está medido en [bench/](../../bench/README.md).

| Paso | Documento | Contenido |
|---|---|---|
| 1 | [Ficha de contexto](01-ficha-de-contexto.md) | El cliente en su voz, 5 anclas con cifras, la tensión dominante y lo ya decidido |
| 2 | [Supuestos](02-supuestos.md) | Lo que el cliente no dijo y asumimos, con la pregunta para confirmarlo |
| 3 | [Priorización](03-priorizacion.md) | 18 puntos repartidos entre los 8 atributos de ISO/IEC 25010 |
| 4 | [Escenarios](04-escenarios.md) | 5 escenarios de calidad, uno por tensión, y cuáles son críticos (4 de ellos) |
| 5 | ADRs de los escenarios críticos | Contexto y opciones consideradas, en estado Propuesto |

## ADRs de esta entrega

| ADR | Escenario | Tensión |
|---|---|---|
| [0008](../adr/0008-grupos-llenos-en-el-pico-de-matricula.md) | E1 | Recomendaciones correctas (cupo real) frente a latencia y carga |
| [0009](../adr/0009-tiempo-maximo-de-la-busqueda.md) | E2 | Latencia acotada frente al top 5 exacto |
| [0010](../adr/0010-deteccion-de-cambios-en-el-sdk.md) | E3 | No cargar la base académica frente a la frescura y la facilidad de instalación |
| [0011](../adr/0011-repartir-la-demanda-entre-grupos.md) | E4 | Recomendaciones que se puedan matricular frente a la preferencia individual (efecto manada) |

**Por qué estos cuatro:** cada uno enfrenta dos atributos altos. E4 se suma porque el front que
ve el estudiante es nuestro: lo que cuesta repartir la demanda cae en nuestra usabilidad. E5 (el
motor no responde a tiempo) no lleva ADR: depende de la fiabilidad, que tiene 1 punto porque el
motor funciona en paralelo con el sistema de matrícula.

Los ADRs quedan en estado **Propuesto**: la decisión y sus consecuencias se completan en el
Bloque 3, cuando se elija entre las opciones con las tácticas.
