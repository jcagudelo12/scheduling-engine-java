# 4. Escenarios de calidad

Un escenario por tensión, no por atributo. Cada uno indica de qué supuestos depende
([02-supuestos.md](02-supuestos.md)).

## E1. Un grupo recomendado se llena durante el pico

| Parte | Contenido |
|---|---|
| Atributo de calidad | Adecuación funcional (corrección), con restricción de desempeño y de compatibilidad |
| Fuente del estímulo | El sistema académico, que registra matrículas durante la apertura de un turno |
| Estímulo | Un grupo pasa de 1 a 0 cupos mientras hay estudiantes consultando combinaciones que lo incluyen |
| Artefacto | La réplica del catálogo en el motor, la generación de combinaciones y el canal de cambios desde la institución |
| Entorno | Apertura de turno: 4.000 estudiantes en 15 minutos, hasta 30 matrículas por segundo y 5.000 grupos; la base académica, a plena carga |
| Respuesta | El motor deja de ofrecer el grupo lleno. Mientras no tenga certeza de un cupo, lo indica en la respuesta en vez de presentarlo como disponible. Las demás consultas no se retrasan |
| Medida de la respuesta | Desde que el sistema académico confirma la matrícula del último cupo hasta que ninguna respuesta del motor lo ofrece como disponible: **≤ 5 s (p99)**. Menos del **2 %** de las combinaciones entregadas en el pico contienen un grupo ya lleno en el momento de responder. La latencia del motor se mantiene en **≤ 200 ms (p99)** |

Depende de **S1** (los 5 s), **S2** (los 200 ms) y **S4** (no hay reservas).

## E2. Una búsqueda que explota

| Parte | Contenido |
|---|---|
| Atributo de calidad | Eficiencia de desempeño (comportamiento temporal), en tensión con adecuación funcional (exactitud del top 5) |
| Fuente del estímulo | Un estudiante con 7 materias habilitadas y sin filtros que reduzcan los grupos |
| Estímulo | Pide sus 5 mejores combinaciones con "menos huecos" como primer criterio, el que peor poda porque su cota es relajada (ADR 0006) |
| Artefacto | El solver de ramificación y poda del motor |
| Entorno | Pico de turno, con la instancia atendiendo además otras solicitudes. Materias de hasta 8 grupos: hasta 2,1 millones de combinaciones antes de podar |
| Respuesta | Devuelve 5 combinaciones válidas y ordenadas. Si no puede garantizar que son las 5 mejores, lo indica. Nunca devuelve una combinación inválida, y esta búsqueda no degrada las de otros estudiantes |
| Medida de la respuesta | **≤ 200 ms (p99)** para todo perfil de hasta 7 materias × 8 grupos. Al menos el **99 %** de las respuestas son el top 5 exacto, comprobado contra la enumeración completa en pruebas. La p99 de las demás solicitudes concurrentes no sube más del **10 %** |

Depende de **S2** (los 200 ms), **S6** (aceptar "no garantizado") y **S7** (el peor caso).

## E3. El SDK detecta cambios sin cargar la base académica

| Parte | Contenido |
|---|---|
| Atributo de calidad | Compatibilidad (coexistencia), en tensión con adecuación funcional (frescura) y portabilidad (instalabilidad) |
| Fuente del estímulo | El agente SDK instalado en la universidad ([ADR 0007](../adr/0007-sdk-de-integracion-propio.md)) |
| Estímulo | Se registran matrículas que cambian cupos en la base académica, y el agente debe detectarlas y enviarlas al motor |
| Artefacto | El mecanismo de detección de cambios del SDK y la base de datos académica |
| Entorno | Apertura de turno: hasta 30 cambios por segundo, con la base atendiendo la matrícula, que es su carga máxima. TI solo da un usuario de lectura y no permite instalar nada en la base |
| Respuesta | El agente detecta y envía cada cambio de cupo sin modificar la base ni el sistema académico. Si la base se pone lenta, reduce su propia carga antes que afectar la matrícula |
| Medida de la respuesta | Uso de la base por el agente **≤ 5 %** de su CPU durante el turno. Retraso desde que se confirma la matrícula hasta que el motor la recibe: **≤ 4 s (p99)**, lo que deja 1 s de los 5 de E1 para el motor. Ningún cambio se pierde ni se duplica. Instalación en una universidad nueva: **≤ 1 día** de un ingeniero, solo configurando |

Depende de **S1** (el presupuesto de 5 s) y **S3** (el costo de leer el catálogo).

## E4. Todos reciben los mismos grupos (efecto manada)

| Parte | Contenido |
|---|---|
| Atributo de calidad | Adecuación funcional (pertinencia: que lo recomendado siga siendo matriculable), en tensión con usabilidad (que cada estudiante reciba lo que prefiere) |
| Fuente del estímulo | Estudiantes del mismo turno con perfiles parecidos: mismo semestre, mismas materias y preferencia por la mañana sin huecos |
| Estímulo | Consultan sus 5 mejores combinaciones en los primeros 15 minutos del turno |
| Artefacto | Los criterios con que el motor ordena el top 5 |
| Entorno | Apertura de turno. Por ejemplo, 600 estudiantes de tercer semestre comparten las mismas 5 materias, con grupos de 35 cupos |
| Respuesta | Las recomendaciones siguen siendo matriculables para la mayoría: el motor no envía a mucha más gente a un grupo de la que cabe en él |
| Medida de la respuesta | En una ventana de 5 minutos, ningún grupo aparece en la primera combinación de más de **1,5 veces su cupo restante** de estudiantes. Al menos el **80 %** de los estudiantes encuentra disponible una de sus 5 combinaciones al matricular. Se verifica con el simulador y un escenario generado con la distribución de S5 |

Depende de **S5** (perfiles homogéneos).

## ¿Cuáles son críticos?

Prueba del widget. Un escenario es crítico cuando su atributo dominante está entre los más
altos **y** cumplirlo cuesta otro atributo también alto:

| Escenario | ¿Dominante alto? | ¿Cuesta otro atributo alto? | ¿Crítico? |
|---|---|---|---|
| E1 | Sí: adecuación funcional (5) | Sí: desempeño (4) y compatibilidad (3) | **Sí → [ADR 0008](../adr/0008-grupos-llenos-en-el-pico-de-matricula.md)** |
| E2 | Sí: desempeño (4) | Sí: adecuación funcional (5) | **Sí → [ADR 0009](../adr/0009-tiempo-maximo-de-la-busqueda.md)** |
| E3 | Sí: compatibilidad (3) | Sí: adecuación funcional (5) | **Sí → [ADR 0010](../adr/0010-deteccion-de-cambios-en-el-sdk.md)** |
| E4 | Sí: adecuación funcional (5) | Sí: usabilidad, porque el front que ve el estudiante es nuestro | **Sí → [ADR 0011](../adr/0011-repartir-la-demanda-entre-grupos.md)** |

**Por qué E4 lleva ADR:** el front con el que el estudiante elige filtros, materias y ve sus 5
mejores combinaciones lo construimos nosotros. Repartir la demanda cambia directamente lo que el
estudiante ve y elige, así que lo que se paga en usabilidad es de nuestro producto, no del portal
de la institución. Además encierra una decisión de producto (colectivo frente a individual) que
debe quedar registrada.
