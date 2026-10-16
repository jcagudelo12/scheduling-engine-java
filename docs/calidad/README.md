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
| 4 | [Escenarios](04-escenarios.md) | 4 escenarios de calidad, uno por tensión, y cuáles son críticos |
| 5 | ADRs de los escenarios críticos | Contexto y opciones consideradas, en estado Propuesto |

## ADRs de esta entrega

| ADR | Escenario | Tensión |
|---|---|---|
| [0008](../adr/0008-grupos-llenos-en-el-pico-de-matricula.md) | E1 | Recomendaciones correctas (cupo real) frente a latencia y carga |
| [0009](../adr/0009-tiempo-maximo-de-la-busqueda.md) | E2 | Latencia acotada frente al top 5 exacto |
| [0010](../adr/0010-deteccion-de-cambios-en-el-sdk.md) | E3 | No cargar la base académica frente a la frescura y la facilidad de instalación |

**Por qué estos tres y no el cuarto:** E1, E2 y E3 enfrentan dos atributos altos de la
priorización. E4 (efecto manada) es importante, pero lo que cuesta cae en la usabilidad, que
puntuamos bajo. Su decisión de producto se trata como una opción dentro del
[ADR 0008](../adr/0008-grupos-llenos-en-el-pico-de-matricula.md).

Los ADRs quedan en estado **Propuesto**: la decisión y sus consecuencias se completan en el
Bloque 3, cuando se elija entre las opciones con las tácticas.
