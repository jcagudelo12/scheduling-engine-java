# 0009. Acotar el tiempo de búsqueda de las 5 mejores combinaciones

- **Estado:** Propuesto
- **Fecha:** 2026-10-16
- **Escenario:** [E2](../calidad/04-escenarios.md#e2-una-búsqueda-que-explota)
- **Relacionado con:** [0006](0006-mejores-combinaciones-con-ramificacion-y-poda.md) (top 5
  exacto con ramificación y poda)

## Contexto

| Parte | Contenido |
|---|---|
| Atributo de calidad | Eficiencia de desempeño (comportamiento temporal), en tensión con adecuación funcional (exactitud del top 5) |
| Fuente del estímulo | Un estudiante con 7 materias habilitadas y sin filtros que reduzcan los grupos |
| Estímulo | Pide sus 5 mejores combinaciones con "menos huecos" como primer criterio, el que peor poda porque su cota es relajada (ADR 0006) |
| Artefacto | El solver de ramificación y poda del motor |
| Entorno | Pico de turno, con la instancia atendiendo además otras solicitudes. Materias de hasta 8 grupos: hasta 2,1 millones de combinaciones antes de podar |
| Respuesta | Devuelve 5 combinaciones válidas y ordenadas. Si no puede garantizar que son las 5 mejores, lo indica. Nunca devuelve una combinación inválida, y esta búsqueda no degrada las de otros estudiantes |
| Medida de la respuesta | **≤ 200 ms (p99)** para todo perfil de hasta 7 materias × 8 grupos. Al menos el **99 %** de las respuestas son el top 5 exacto, comprobado contra la enumeración completa en pruebas. La p99 de las demás solicitudes concurrentes no sube más del **10 %** |

Supuestos de los que depende: S2, S6 y S7 ([02-supuestos.md](../calidad/02-supuestos.md)).

Restricción: el ADR 0006 decidió que el resultado es **exacto** y descartó las heurísticas que
no lo garantizan. No dijo qué pasa cuando la búsqueda exacta no cabe en el tiempo disponible.
Ese vacío es lo que decide este ADR.

**La brecha:** la búsqueda de hoy (sin criterios ni filtros) responde en milisegundos porque se
detiene en las primeras combinaciones válidas. Buscar las 5 *mejores* obliga a recorrer el árbol
hasta demostrar que no hay otras mejores, y con la cota relajada de "huecos" la poda puede no
alcanzar en el peor caso. No hay hoy ningún límite al tiempo que puede consumir una solicitud.

## Opciones consideradas

### A. Búsqueda exacta sin límite

Se mantiene lo que dice el ADR 0006 tal cual: la búsqueda termina cuando demuestra que tiene las
5 mejores, tarde lo que tarde.

- **Gana:** siempre el top 5 exacto y siempre la misma respuesta para la misma consulta. No hay
  nada nuevo que explicar en pantalla.
- **Paga:** latencia sin cota en el peor caso. Una búsqueda difícil ocupa un núcleo mientras dura
  y retrasa a los demás estudiantes de esa instancia.
- **Sacrifica:** desempeño (cola larga de latencia y aislamiento entre solicitudes).
- **Qué mediríamos:** p99 y máximo por perfil, con un generador de los peores casos (7 × 8, sin
  filtros, "huecos" primero), y el efecto sobre la p99 de las solicitudes normales concurrentes.

### B. Presupuesto de tiempo y la mejor respuesta encontrada

La búsqueda tiene un tiempo máximo, por ejemplo 150 ms. Si se cumple antes de terminar, devuelve
las 5 mejores que encontró hasta ese momento, marcadas como "no garantizadas".

- **Gana:** latencia acotada para todos los perfiles. El orden de exploración del ADR 0006 hace
  que las primeras combinaciones encontradas ya sean buenas.
- **Paga:** la misma consulta puede dar respuestas distintas según la carga de la máquina en ese
  momento, lo que es difícil de probar y de explicar. Hay que decir en pantalla que podría haber
  mejores (S6).
- **Sacrifica:** adecuación funcional (exactitud en los casos difíciles) y reproducibilidad.
- **Qué mediríamos:** porcentaje de respuestas cortadas por el presupuesto, y distancia de su
  puntaje respecto al top 5 exacto calculado sin límite.

### C. Presupuesto de trabajo en lugar de tiempo

Igual que B, pero el límite es la cantidad de ramas exploradas y no los milisegundos. La misma
consulta sobre el mismo catálogo siempre explora lo mismo y da la misma respuesta.

- **Gana:** latencia acotada y respuestas reproducibles: se puede probar y depurar.
- **Paga:** el tiempo real varía con la carga de la máquina, así que el límite se calibra con un
  margen. Comparte con B la pérdida de exactitud en los casos difíciles.
- **Sacrifica:** adecuación funcional (exactitud), y algo de precisión en la cota de tiempo.
- **Qué mediríamos:** ramas exploradas por el p99 de los perfiles reales, y tiempo de ese número
  de ramas con la instancia al 70 % de CPU.

### D. Pedirle al estudiante que acote antes de buscar

Antes de buscar, el motor estima el tamaño del espacio (producto de los grupos por materia). Si
supera un umbral, no busca: le pide al estudiante un filtro más (jornada, días libres o sede) o
que ordene menos criterios.

- **Gana:** siempre exacto y rápido. Los filtros estrictos del ADR 0006 reducen el árbol antes de
  buscar.
- **Paga:** fricción para el estudiante justo en el momento de mayor presión.
- **Sacrifica:** usabilidad.
- **Decisión de producto:** exige más pasos precisamente a los estudiantes con más materias, que
  suelen ser los que van atrasados o están repitiendo. Decide a quién se le pone más difícil la
  matrícula.
- **Qué mediríamos:** porcentaje de consultas reales que superan el umbral, y cuántas de ellas se
  abandonan sin completar el filtro.

### E. Guardar los resultados de los perfiles frecuentes

Muchos estudiantes piden lo mismo: las mismas materias, los mismos filtros y los mismos criterios
(S5). El motor guarda el resultado de cada consulta frecuente y lo reutiliza hasta que cambia el
cupo de alguno de sus grupos.

- **Gana:** las consultas repetidas responden al instante, aunque su búsqueda sea cara.
- **Paga:** en el pico cambian hasta 30 cupos por segundo, así que los resultados guardados se
  invalidan constantemente, justo cuando más se necesitan. Ocupa memoria. No ayuda al estudiante
  con un perfil poco común, que puede ser el peor caso.
- **Sacrifica:** adecuación funcional (riesgo de servir un resultado que ya no corresponde al
  cupo actual, en tensión directa con el [ADR 0008](0008-grupos-llenos-en-el-pico-de-matricula.md))
  y memoria.
- **Qué mediríamos:** tasa de aciertos durante un turno simulado, con la tasa de cambios del
  ancla 2.

### Resumen

| Opción | Gana | Paga | ¿Cumple la medida? |
|---|---|---|---|
| A · Exacta sin límite | Top 5 exacto y reproducible | Cola de latencia sin cota | No se sabe: hay que medir el peor caso. Si supera los 200 ms, no cumple |
| B · Presupuesto de tiempo | Latencia acotada | Respuestas que varían con la carga | Sí en tiempo. El 99 % de exactas depende de medirlo |
| C · Presupuesto de trabajo | Latencia acotada y reproducible | El tiempo real varía con la carga | Sí, con margen. El 99 % de exactas depende de medirlo |
| D · Pedir que acote | Exacto y rápido | Fricción y una decisión de a quién cuesta más | Sí, a costa de la usabilidad |
| E · Guardar resultados | Instantáneo en perfiles repetidos | Se invalida en el pico y choca con el ADR 0008 | No en el peor caso, que es un perfil poco común |

**Complementario a cualquier opción:** mejorar la cota de "huecos" o el orden de exploración
reduce el tamaño del árbol, pero no garantiza un límite. Es optimización, no una alternativa de
arquitectura.

## Decisión

Abierta. Se elige en el Bloque 3, con las tácticas. Antes conviene medir la opción A con los
peores casos: si cabe en 200 ms, la tensión desaparece.
