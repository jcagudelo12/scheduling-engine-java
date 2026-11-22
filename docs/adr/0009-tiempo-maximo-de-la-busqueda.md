# 0009. Acotar el tiempo de búsqueda de las 5 mejores combinaciones

- **Estado:** Aceptado
- **Fecha:** 2026-11-22
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

**Restricción**: el ADR 0006 decidió que el resultado es **exacto** y descartó las heurísticas que
no lo garantizan. No dijo qué pasa cuando la búsqueda exacta no cabe en el tiempo disponible.
Ese vacío es lo que decide este ADR.

**La brecha**: la búsqueda de hoy (sin criterios ni filtros) responde en milisegundos porque se
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
- **Qué mediremos:** p99 y máximo por perfil, con un generador de los peores casos (7 × 8, sin
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
- **Qué mediremos:** porcentaje de respuestas cortadas por el presupuesto, y distancia de su
  puntaje respecto al top 5 exacto calculado sin límite.

### C. Presupuesto de trabajo en lugar de tiempo

Igual que B, pero el límite es la cantidad de ramas exploradas y no los milisegundos. La misma
consulta sobre el mismo catálogo siempre explora lo mismo y da la misma respuesta.

- **Gana:** latencia acotada y respuestas reproducibles: se puede probar y depurar.
- **Paga:** el tiempo real varía con la carga de la máquina, así que el límite se calibra con un
  margen. Comparte con B la pérdida de exactitud en los casos difíciles.
- **Sacrifica:** adecuación funcional (exactitud), y algo de precisión en la cota de tiempo.
- **Qué mediremos:** ramas exploradas por el p99 de los perfiles reales, y tiempo de ese número
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
- **Qué mediremos:** porcentaje de consultas reales que superan el umbral, y cuántas de ellas se
  abandonan sin completar el filtro.

### E. Guardar los resultados de los perfiles frecuentes

Muchos estudiantes piden lo mismo: las mismas materias, los mismos filtros y los mismos criterios
(S5). El motor guarda el resultado de cada consulta frecuente y lo reutiliza hasta que cambia el
cupo de alguno de sus grupos.

- **Gana:** las consultas repetidas responden al instante, aunque su búsqueda sea cara.
- **Paga:** en el pico cambian hasta 30 cupos por segundo, así que los resultados guardados se
  invalidan constantemente, justo cuando más se necesitan. Ocupa la memoria. No ayuda al estudiante
  con un perfil poco común, que puede ser el peor caso.
- **Sacrifica:** adecuación funcional (riesgo de servir un resultado que ya no corresponde al
  cupo actual, en tensión directa con el [ADR 0008](0008-grupos-llenos-en-el-pico-de-matricula.md))
  y memoria.
- **Qué mediremos:** tasa de aciertos durante un turno simulado, con la tasa de cambios del
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

**Opción B: un tiempo máximo de búsqueda con la mejor respuesta encontrada, marcada como
incompleta.** Se descarta A (sin cota de latencia, no cumple E2 en el peor caso) y se deja C, D y E
fuera por ahora (ver abajo).

Cómo queda:

- `Problem` lleva un `timeLimit` opcional. `ForwardCheckingSolver` y `BacktrackingSolver` leen
  `System.nanoTime()` cada 256 nodos (barato: una máscara por nodo) y, si el tiempo venció, cortan
  y devuelven lo encontrado. `Solver.search(Problem)` devuelve un `SearchResult` con
  `complete`; `solveScored` y `solve` delegan y descartan el indicador.
- `GenerateCombinations` y `SuggestAlternatives` pasan el límite, configurable con
  `SCHED_SEARCH_TIMEOUT_MS` (por defecto **150**, bajo los 200 ms de E2 para dejar margen a la
  lectura del catálogo y la serialización; **0** lo desactiva). Las respuestas de `/combinations` y
  `/alternatives` incluyen `"complete": true|false`.
- **La exactitud no cambia cuando la búsqueda termina.** Con `complete = true` los resultados son
  exactamente los del ADR 0006 (top-K por costo, mismo orden, mismos desempates). Un tiempo
  máximo holgado produce resultados idénticos a no tenerlo, lo que se comprueba con una propiedad
  (jqwik) contra la búsqueda sin límite.
- **Con `complete = false` no hay garantía de top-K.** Con costo, son las mejores combinaciones
  encontradas hasta que venció el tiempo, que pueden no ser las de menor costo ni llegar a las K
  pedidas (incluso ninguna, si el árbol no entregó una antes). Toda combinación devuelta es
  válida: sin cruces y con un grupo por curso. Sin costo, el corte devuelve las combinaciones
  halladas hasta ese momento.

### Por qué B

- **Es la única opción que acota la latencia sin quitarle nada al estudiante.** D mueve el costo
  al estudiante en el peor momento y E no ayuda al perfil poco común, que es el peor caso. A y B
  se distinguen por una sola cosa: con B hay una cota de tiempo.
- **El motor no tenía ninguna cota.** Una solicitud con cursos que se cruzan por pares sin
  solución obliga a recorrer un árbol exponencial. El caso de prueba (12 cursos de 10 grupos,
  cruces por índice de grupo, principio del palomar) no tiene solución y ni MRV ni forward
  checking lo ven antes del nivel 10: el árbol es del orden de 10! nodos. Con el límite en
  50 ms, ambos solvers terminan en menos de 200 ms y marcan `complete = false`.
- **Los datos del benchmark no contradicen la decisión.** La prueba de carga
  (`bench/results/report.md`) mide perfiles típicos y no el peor caso: p99 de 3,8 ms a 200
  solicitudes/s en frío, y 50 µs por búsqueda en el microbenchmark de 6 × 5. Para estos perfiles
  la búsqueda termina mucho antes del límite y `complete` es `true`: B no cambia la respuesta
  del caso normal. Lo que mide E2, el 7 × 8 sin filtros con "huecos" primero, no está en esos
  datos, y por eso hace falta el límite en vez de confiar en que A cabe.
- **Mantiene la garantía donde importa.** El resultado solo es "no garantizado" cuando de otro
  modo la solicitud no habría terminado a tiempo, que es exactamente lo que pide E2 ("si no puede
  garantizar que son las 5 mejores, lo indica").

### Por qué no C (presupuesto de trabajo)

C da respuestas reproducibles, pero E2 se mide en milisegundos y el número de ramas que cabe en
150 ms depende de la máquina y de su carga, así que habría que calibrarlo y recalibrarlo. El
tiempo de reloj acota directamente lo que se promete. La falta de reproducibilidad de B solo
afecta a las respuestas incompletas, que están marcadas. Si se necesitara reproducibilidad en
pruebas, C puede agregarse como un segundo límite sin cambiar la API (`search` ya devuelve
`complete`).

## Consecuencias

- **Gana:** la latencia de una solicitud queda acotada (150 ms más la demora del corte, de
  microsegundos) para cualquier entrada, y una solicitud difícil ya no ocupa un núcleo sin fin ni
  degrada a las demás.
- **Paga:** la misma consulta puede dar respuestas distintas, con `complete` distinto, según la
  carga de la máquina. El portal debe leer `complete` y avisar al estudiante cuando es `false`
  ("podría haber mejores combinaciones", S6).
- **Pendiente de medir:** el porcentaje de respuestas cortadas y la distancia de su puntaje al
  top 5 exacto con perfiles 7 × 8 reales (el 99 % de E2), y el efecto sobre la p99 de las demás
  solicitudes. El límite de 150 ms es un valor inicial: se ajusta con esas mediciones.
  El motor ya cuenta las búsquedas cortadas (`sched_search_incomplete_total`, ver
  [arquitectura.md](../arquitectura.md#observabilidad)); falta recogerlas en un pico real.
- **No resuelve** la cota de entrada: el tiempo máximo acota cada solicitud, no su cantidad. El
  tope de cursos y de tamaño de la solicitud se trata aparte (validación de entrada).
- Las respuestas incompletas no se guardan ni se reutilizan: cada solicitud calcula la suya.
