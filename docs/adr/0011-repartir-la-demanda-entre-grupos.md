# 0011. Evitar que el top 5 envíe a todos los estudiantes a los mismos grupos

- **Estado:** Propuesto
- **Fecha:** 2026-10-20
- **Escenario:** [E4](../calidad/04-escenarios.md#e4-todos-reciben-los-mismos-grupos-efecto-manada)
- **Relacionado con:** [0006](0006-mejores-combinaciones-con-ramificacion-y-poda.md) (criterios del
  top 5), [0005](0005-varias-instancias-con-historial-compartido.md) (instancias sin estado
  compartido), [0008](0008-grupos-llenos-en-el-pico-de-matricula.md) (grupos llenos; su opción E es
  la opción C de este ADR)

## Contexto

| Parte | Contenido |
|---|---|
| Atributo de calidad | Adecuación funcional (pertinencia: que lo recomendado siga siendo matriculable), en tensión con usabilidad (que cada estudiante reciba lo que prefiere) |
| Fuente del estímulo | Estudiantes del mismo turno con perfiles parecidos: mismo semestre, mismas materias y preferencia por la mañana sin huecos |
| Estímulo | Consultan sus 5 mejores combinaciones en los primeros 15 minutos del turno |
| Artefacto | Los criterios con que el motor ordena el top 5 |
| Entorno | Apertura de turno. Por ejemplo, 600 estudiantes de tercer semestre comparten las mismas 5 materias, con grupos de 35 cupos |
| Respuesta | Las recomendaciones siguen siendo matriculables para la mayoría: el motor no envía a mucha más gente a un grupo de la que cabe en él |
| Medida de la respuesta | En una ventana de 5 minutos, ningún grupo aparece en la primera combinación de más de **1,5 veces su cupo restante** de estudiantes. Al menos el **80 %** de los estudiantes encuentra disponible una de sus 5 combinaciones al matricular. Se verifica con el simulador y un escenario generado con la distribución de S5 |

Supuestos de los que depende: S5 ([02-supuestos.md](../calidad/02-supuestos.md)).

**Restricciones:** el top 5 se ordena con los criterios que elige el estudiante y es exacto
(ADR 0006). Las instancias de consulta no comparten estado entre sí, salvo el historial del
catálogo (ADR 0005), así que ninguna sabe qué recomendaron las demás.

**La brecha:** hoy el top 5 depende solo del catálogo y de las preferencias del estudiante. Dos
estudiantes con el mismo perfil reciben exactamente la misma primera combinación. Con 600
estudiantes iguales y grupos de 35 cupos, el motor puede enviar a un mismo grupo 17 veces más
gente de la que cabe. El criterio "más cupo disponible" del ADR 0006 no lo evita: todos ven el
mismo cupo en el mismo momento, así que todos se mueven juntos al mismo grupo "más libre".

## Opciones consideradas

### A. Ordenar solo por las preferencias del estudiante

Se mantiene el ADR 0006 tal cual: cada estudiante recibe su top 5 exacto, sin importar lo que se
recomendó a los demás.

- **Gana:** resultado exacto, reproducible y fácil de explicar: "estas son tus 5 mejores según lo
  que pediste". Sin estado nuevo.
- **Paga:** la concentración del escenario ocurre tal cual. Las primeras combinaciones de los
  perfiles comunes se agotan en minutos y el estudiante vuelve a consultar, lo que suma carga.
- **Sacrifica:** adecuación funcional (pertinencia: recomendaciones que ya no se pueden
  matricular).
- **Qué mediremos:** en una simulación con la distribución de S5, la proporción máxima entre los
  estudiantes que reciben un grupo en su primera combinación y su cupo restante. Sirve como línea
  base para las demás opciones.

### B. Diversificar las 5 combinaciones de cada estudiante

El top 5 exige que las combinaciones no se apoyen todas en los mismos grupos: por ejemplo, que
ningún grupo aparezca en más de 3 de las 5. Si se llena el grupo de la primera, el estudiante
tiene alternativas reales en su misma lista.

- **Gana:** cada estudiante queda protegido ante un grupo lleno, sin estado compartido entre
  instancias. Sigue usando solo sus preferencias.
- **Paga:** no reduce la concentración sobre la primera combinación, solo el daño cuando se
  llena. Las combinaciones 2 a 5 pueden ser peores que las que daría el top 5 estricto. La
  restricción complica la poda y alarga la búsqueda (ver [ADR 0009](0009-tiempo-maximo-de-la-busqueda.md)).
- **Sacrifica:** adecuación funcional (el top 5 deja de ser el estricto) y algo de desempeño.
- **Qué mediremos:** porcentaje de estudiantes que encuentra disponible al menos una de sus 5
  combinaciones al matricular, y tiempo adicional de búsqueda en los peores casos de E2.

### C. Penalizar los grupos que ya se están recomendando mucho

El motor lleva un contador compartido de cuántas veces recomendó cada grupo en los últimos
minutos, frente a su cupo restante. Al ordenar, entre combinaciones casi igual de buenas para el
estudiante, prefiere las que menos gente está mirando. Es la opción E del ADR 0008.

- **Gana:** ataca la causa: reparte la demanda antes de que los grupos se llenen. Es la única
  opción que actúa directamente sobre la primera medida del escenario.
- **Paga:** las instancias necesitan un contador compartido y actualizado en segundos, que hoy no
  existe y rompe la independencia entre instancias del ADR 0005. La primera combinación deja de
  ser la mejor según el estudiante, y hay que explicarlo en pantalla.
- **Sacrifica:** usabilidad (lo recomendado no es exactamente lo preferido) y desempeño (estado
  compartido en cada consulta).
- **Decisión de producto:** pone el beneficio colectivo por encima de la preferencia individual.
  Favorece a quien consulta más tarde y perjudica a quien tiene las preferencias más comunes. La
  debe aprobar Registro.
- **Qué mediremos:** en la simulación con S5, la misma proporción de la opción A y la diferencia
  de puntaje entre lo recomendado y el top 5 sin penalizar.

### D. Sortear entre combinaciones casi empatadas

Las combinaciones cuyo puntaje está dentro de un margen pequeño respecto a la mejor se consideran
empatadas, y su orden se sortea por estudiante. El sorteo usa el identificador del estudiante
como semilla, así que cada estudiante ve siempre el mismo orden.

- **Gana:** reparte la demanda sin estado compartido: cada instancia lo calcula sola. Es
  reproducible por estudiante, así que se puede probar.
- **Paga:** dos estudiantes con las mismas preferencias ven primeras combinaciones distintas, y
  puede percibirse como arbitrario. El efecto depende de cuántas combinaciones caen dentro del
  margen: si hay pocas casi empatadas, reparte poco.
- **Sacrifica:** usabilidad (el orden ya no es estrictamente el preferido) y algo de adecuación
  funcional (la primera no siempre es la de mayor puntaje).
- **Decisión de producto:** el azar decide quién recibe la mejor combinación entre las casi
  iguales. Es neutral entre estudiantes, pero hay que poder explicarlo si alguien reclama.
- **Qué mediremos:** en la simulación con S5, la proporción de la opción A según el tamaño del
  margen, y cuántos estudiantes reciben una primera combinación fuera de su top 1 estricto.

### E. Mostrar la competencia y dejar decidir al estudiante

El motor no cambia el orden, pero la pantalla muestra por cada grupo su cupo restante y cuántos
estudiantes lo están viendo: "quedan 4 cupos, 40 personas lo vieron en los últimos 5 minutos". El
estudiante elige si arriesga o toma otra combinación.

- **Gana:** el top 5 sigue siendo exacto y la decisión queda en el estudiante, con información.
- **Paga:** necesita el mismo contador compartido de la opción C. Puede generar ansiedad o
  carreras por los últimos cupos, y no garantiza que la demanda se reparta.
- **Sacrifica:** usabilidad (más información que procesar en el momento de más presión) y
  desempeño (estado compartido).
- **Qué mediremos:** en una prueba con estudiantes, cuántos cambian de combinación al ver la
  competencia y si eso reduce la concentración medida en la opción A.

### Resumen

| Opción | Gana | Paga | ¿Cumple la medida? |
|---|---|---|---|
| A · Solo preferencias | Exacto, simple y sin estado | Concentración tal cual | No: es la línea base del problema |
| B · Diversificar las 5 | Alternativas reales por estudiante | No reduce la concentración en la primera; búsqueda más lenta | En parte: ayuda al 80 %, no al tope de 1,5 veces |
| C · Penalizar lo muy recomendado | Reparte la demanda en origen | Contador compartido entre instancias | Probablemente sí; hay que simularlo |
| D · Sortear casi empates | Reparte sin estado compartido | Orden percibido como arbitrario | Depende del margen; hay que simularlo |
| E · Mostrar la competencia | Top 5 exacto y decisión informada | Contador compartido y posible carrera | Incierto: depende del comportamiento de los estudiantes |

Las opciones se combinan: B y D no necesitan estado compartido y pueden aplicarse juntas. C y E
comparten el mismo contador, así que si se construye para una, la otra cuesta poco.

## Decisión

Abierta. Se elige en el Bloque 3, con las tácticas. Antes conviene correr la simulación de la
opción A con la distribución de S5: si la concentración real está por debajo de 1,5 veces el
cupo, la tensión desaparece.
