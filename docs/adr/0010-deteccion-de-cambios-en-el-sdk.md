# 0010. Detectar los cambios de cupo en el SDK sin cargar la base académica

- **Estado:** Propuesto
- **Fecha:** 2026-10-16
- **Escenario:** [E3](../calidad/04-escenarios.md#e3-el-sdk-detecta-cambios-sin-cargar-la-base-académica)
- **Relacionado con:** [0007](0007-sdk-de-integracion-propio.md) (SDK como agente),
  [0008](0008-grupos-llenos-en-el-pico-de-matricula.md) (usa el retraso que fija este ADR)

## Contexto

| Parte | Contenido |
|---|---|
| Atributo de calidad | Compatibilidad (coexistencia), en tensión con adecuación funcional (frescura) y portabilidad (instalabilidad) |
| Fuente del estímulo | El agente SDK instalado en la universidad ([ADR 0007](0007-sdk-de-integracion-propio.md)) |
| Estímulo | Se registran matrículas que cambian cupos en la base académica, y el agente debe detectarlas y enviarlas al motor |
| Artefacto | El mecanismo de detección de cambios del SDK y la base de datos académica |
| Entorno | Apertura de turno: hasta 30 cambios por segundo, con la base atendiendo la matrícula, que es su carga máxima. TI solo da un usuario de lectura y no permite instalar nada en la base |
| Respuesta | El agente detecta y envía cada cambio de cupo sin modificar la base ni el sistema académico. Si la base se pone lenta, reduce su propia carga antes que afectar la matrícula |
| Medida de la respuesta | Uso de la base por el agente **≤ 5 %** de su CPU durante el turno. Retraso desde que se confirma la matrícula hasta que el motor la recibe: **≤ 4 s (p99)**, lo que deja 1 s de los 5 de E1 para el motor. Ningún cambio se pierde ni se duplica. Instalación en una universidad nueva: **≤ 1 día** de un ingeniero, solo configurando |

Supuestos de los que depende: S1 y S3 ([02-supuestos.md](../calidad/02-supuestos.md)).

Restricción: el ADR 0007 (también Propuesto) propone como primera versión consultar la base
periódicamente y comparar. Este ADR abre esa parte de la decisión con sus alternativas, porque es
donde se cruzan el "actualizado" del cliente y el tope de TI.

**La brecha:** con el supuesto S3, leer el catálogo completo cada 4 s consume cerca del 5 % de la
CPU de la base, justo en el límite de TI y sin margen. Leerlo más seguido rompe el tope; leerlo
menos seguido rompe el retraso de 4 s.

## Opciones consideradas

### A. Leer todo el catálogo periódicamente y comparar

Cada N segundos el agente lee el catálogo completo con la consulta configurada, lo compara con la
lectura anterior y envía las diferencias (la versión 1 del ADR 0007).

- **Gana:** funciona con cualquier base de datos y solo necesita un usuario de lectura. Es lo más
  fácil de instalar en una universidad nueva, porque basta con escribir una consulta. Nunca pierde
  un cambio: compara estados completos.
- **Paga:** la carga crece con el tamaño del catálogo y con la frecuencia, no con los cambios
  reales. El retraso es, como mínimo, el intervalo entre lecturas.
- **Sacrifica:** compatibilidad (carga constante aunque nada cambie) o adecuación funcional
  (frescura), según cómo se fije N.
- **Qué mediríamos:** CPU de la base por lectura completa en el catálogo real (confirma S3), y el
  menor N que respeta el 5 %.

### B. Leer solo lo que cambió desde la última lectura

El agente pide únicamente las filas modificadas desde la última lectura, usando una columna de
fecha de modificación o un número de versión que la base ya tenga. Cada cierto tiempo hace una
lectura completa para detectar los grupos eliminados.

- **Gana:** la carga crece con los cambios (30/s), no con el catálogo (5.000 grupos). Permite leer
  cada segundo dentro del tope.
- **Paga:** exige que la base tenga una marca de modificación confiable y con índice, cosa que no
  todas tienen ni todas mantienen bien. Los borrados no se ven hasta la lectura completa.
- **Sacrifica:** portabilidad (depende del esquema de cada universidad, así que la instalación ya
  no es "solo configurar") y algo de adecuación funcional (borrados tardíos).
- **Qué mediríamos:** costo de la consulta incremental con 30 cambios/s, y en cuántas
  universidades candidatas existe esa columna con índice.

### C. Leer el registro de transacciones de la base

El agente lee el registro interno en el que la base anota cada transacción confirmada, en vez de
consultar tablas. Recibe cada cambio de cupo casi al mismo tiempo que se confirma.

- **Gana:** retraso menor a 1 s y carga casi nula: no ejecuta consultas sobre las tablas. Ve
  también los borrados, en el orden exacto.
- **Paga:** necesita permisos especiales que TI hoy no concede (ancla 4) y una configuración
  distinta para cada motor de base de datos. Agrega una pieza de infraestructura que alguien
  debe operar.
- **Sacrifica:** portabilidad (instalabilidad) y compatibilidad en el sentido de los permisos.
- **Decisión de política:** la universidad tendría que dar acceso a un registro que contiene
  todas sus transacciones, no solo las de cupos. Es una decisión de TI y de protección de datos.
- **Qué mediríamos:** retraso de punta a punta en el pico y carga añadida a la base, en una base
  de prueba con el volumen del ancla 2.

### D. Que la base anote los cambios en una tabla aparte

Se agrega a la base un disparador que, en cada matrícula, escribe el grupo afectado en una tabla
de cambios pendientes. El agente lee y vacía esa tabla, que es pequeña.

- **Gana:** carga mínima en cada lectura, sin perder cambios ni borrados, con 1 s de retraso.
- **Paga:** hay que instalar algo dentro de la base académica, y TI no lo permite (ancla 4). Cada
  matrícula hace una escritura más, justo en el momento de mayor carga.
- **Sacrifica:** compatibilidad (modifica y carga el sistema de otro) y portabilidad.
- **Qué mediríamos:** tiempo adicional por matrícula en el sistema académico con el disparador
  activo, a 30 matrículas/s.

### E. Ajustar la frecuencia de lectura a la carga de la base

Sobre A o B: el agente mide cuánto tarda cada lectura. Si la base se pone lenta, espera más entre
lecturas, y cuando está tranquila, lee más seguido. El objetivo es usar siempre como máximo el 5 %.

- **Gana:** nunca supera el tope de TI, aunque la consulta cueste más de lo supuesto. Cumple la
  parte "reduce su propia carga" de la respuesta del escenario.
- **Paga:** el retraso deja de ser fijo: crece justo en el pico, cuando más importa la frescura.
  Hay que informarle al motor que la frescura bajó.
- **Sacrifica:** adecuación funcional (frescura) en los peores minutos.
- **Qué mediríamos:** retraso p99 durante el pico simulado, frente a la CPU de la base usada por el
  agente.

### Resumen

| Opción | Gana | Paga | ¿Cumple la medida? |
|---|---|---|---|
| A · Lectura completa periódica | Instalación trivial y sin pérdidas | Carga fija; retraso igual al intervalo | Justo en el límite si S3 es cierto; no cumple si la consulta cuesta más |
| B · Lectura incremental | Carga proporcional a los cambios | Depende del esquema de cada universidad | Sí, donde exista la marca de modificación |
| C · Registro de transacciones | Menos de 1 s y casi sin carga | Permisos que TI no da y una pieza más que operar | Sí técnicamente; hoy no es viable (ancla 4) |
| D · Tabla de cambios | Exacta y barata de leer | Modifica la base académica | No: viola el ancla 4 |
| E · Frecuencia adaptativa | Nunca rompe el tope de TI | Retraso variable en el pico | Cumple el 5 % siempre; los 4 s, depende de medirlo |

Las opciones se combinan: E se aplica sobre A o B, y una instalación puede empezar con A y
pasar a B o C cuando la universidad lo permita. El SDK lo admite sin cambiar su núcleo, porque
cada mecanismo es un adaptador distinto del puerto `FuenteAcademica` (ADR 0007).

## Decisión

Abierta. Se elige en el Bloque 3, con las tácticas. Antes hay que confirmar S3 con TI: el costo
real de la lectura completa decide si A sirve o no.
