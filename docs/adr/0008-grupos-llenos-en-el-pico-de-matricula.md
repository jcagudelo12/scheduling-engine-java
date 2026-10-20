# 0008. Evitar recomendar grupos que ya se llenaron durante el pico de matrícula

- **Estado:** Propuesto
- **Fecha:** 2026-10-16
- **Escenario:** [E1](../calidad/04-escenarios.md#e1-un-grupo-recomendado-se-llena-durante-el-pico)
- **Relacionado con:** [0003](0003-replica-del-catalogo.md) (réplica y `stale`),
  [0006](0006-mejores-combinaciones-con-ramificacion-y-poda.md) (top 5),
  [0010](0010-deteccion-de-cambios-en-el-sdk.md) (de dónde llegan los cambios)

## Contexto

| Parte | Contenido |
|---|---|
| Atributo de calidad | Adecuación funcional (corrección), con restricción de desempeño y de compatibilidad |
| Fuente del estímulo | El sistema académico, que registra matrículas durante la apertura de un turno |
| Estímulo | Un grupo pasa de 1 a 0 cupos mientras hay estudiantes consultando combinaciones que lo incluyen |
| Artefacto | La réplica del catálogo en el motor, la generación de combinaciones y el canal de cambios desde la institución |
| Entorno | Apertura de turno: 4.000 estudiantes en 15 minutos, hasta 30 matrículas por segundo y 5.000 grupos; la base académica, a plena carga |
| Respuesta | El motor deja de ofrecer el grupo lleno. Mientras no tenga certeza de un cupo, lo indica en la respuesta en vez de presentarlo como disponible. Las demás consultas no se retrasan |
| Medida de la respuesta | Desde que el sistema académico confirma la matrícula del último cupo hasta que ninguna respuesta del motor lo ofrece como disponible: **≤ 5 s (p99)**. Menos del **2 %** de las combinaciones entregadas en el pico contienen un grupo ya lleno en el momento de responder. La latencia del motor se mantiene en **≤ 200 ms (p99)** |

Supuestos de los que depende: S1, S2 y S4 ([02-supuestos.md](../calidad/02-supuestos.md)).

**Restricciones**: el motor no consulta ni escribe en la institución (ADR 0002) y responde desde
una réplica en memoria (ADR 0003). Hoy solo sabe que un grupo se llenó cuando le llega el
cambio, y ese retraso lo define el SDK (ADR 0010).

**La brecha**: el motor recomienda grupos con 1 o 2 cupos como si fueran tan seguros como uno de
30. En el pico, el último cupo de un grupo popular dura unos 17 s, del mismo orden que el tiempo
que tarda el estudiante en leer la pantalla y elegir.

## Opciones consideradas

### A. Revalidar justo antes de matricular

Cuando el estudiante elige una combinación, el front le pide al motor que la compruebe otra vez
contra la réplica más reciente antes de enviarla al sistema académico. Si un grupo se llenó, el
motor propone el reemplazo más cercano.

- **Gana:** detecta los grupos que se llenaron mientras el estudiante decidía, sin tocar la
  institución. La consulta es barata, porque compara una sola combinación.
- **Paga:** una solicitud más por estudiante y un paso más en el front. No mejora la frescura de
  la réplica: sigue limitada al retraso del SDK.
- **Sacrifica:** desempeño (más solicitudes) y algo de usabilidad.
- **Qué mediremos:** porcentaje de matrículas rechazadas por falta de cupo, con y sin
  revalidación, en una simulación del turno.

### B. Empujar los cambios de cupo a la pantalla

El motor mantiene una conexión abierta con cada estudiante que está viendo combinaciones y le
avisa en cuanto un grupo de su pantalla cambia de cupo. La pantalla tacha el grupo o lo actualiza
sin recargar. El canal ya está previsto (ADR 0003, punto 6).

- **Gana:** la información llega a la pantalla menos de 1 s después de llegar al motor. El
  estudiante ve el cambio mientras decide, no al final.
- **Paga:** hasta 4.000 conexiones abiertas en el pico, repartidas entre las instancias. Cada
  instancia debe saber qué grupos está viendo cada conexión. Más memoria y más lógica de reconexión en el front.
- **Sacrifica:** desempeño (memoria y conexiones) y algo de fiabilidad (más piezas que pueden
  fallar).
- **Qué mediremos:** tiempo desde que llega el cambio al motor hasta que se ve en pantalla, y
  memoria por conexión con 4.000 conexiones simultáneas.

### C. Tratar como "en riesgo" los grupos casi llenos

El motor considera la velocidad a la que se llena cada grupo. Si con su cupo actual y su ritmo es
probable que se llene antes de que el estudiante termine de decidir, lo marca "en riesgo" y lo
ubica después de las combinaciones más seguras.

- **Gana:** no necesita infraestructura nueva. Reduce las recomendaciones que se invalidan en
  segundos.
- **Paga:** comete el error contrario: relega grupos que todavía tienen cupo real. Necesita
  medir el ritmo de cada grupo, que en el primer minuto del turno no se conoce.
- **Sacrifica:** adecuación funcional en la otra dirección (cupos reales que no se recomiendan
  primero).
- **Decisión de producto:** relegar un grupo casi lleno equivale a decidir que esos últimos cupos
  son para quien insiste o para quien consultó antes. Es una política de asignación, no solo un
  ajuste técnico.
- **Qué mediremos:** grupos marcados "en riesgo" que no se llenaron en los 2 minutos siguientes
  (falsos llenos), frente a recomendaciones que sí se invalidaron.

### D. Apartar el cupo mientras el estudiante decide

Al elegir una combinación, el sistema académico aparta sus cupos durante unos minutos. Si el
estudiante no se logra matricular a tiempo, los cupos se liberan.

- **Gana:** elimina el rechazo en el momento de matricular: lo elegido está garantizado.
- **Paga:** exige cambiar el sistema académico, que hoy no tiene reservas (S4), y que el motor o
  el portal escriban en él, lo que contradice el ADR 0002. Las reservas abandonadas dejan cupos
  bloqueados en plena apertura.
- **Sacrifica:** compatibilidad (cambios en un sistema que no es nuestro) y eficiencia de uso de
  los cupos.
- **Decisión de producto:** cuánto dura la reserva y cuántas puede tener un estudiante define
  quién bloquea cupos y quién se queda sin ellos.
- **Qué mediremos:** porcentaje de reservas que terminan en matrícula, y cupos-minutos bloqueados
  por reservas abandonadas.

### E. Repartir la demanda entre grupos equivalentes

Al ordenar el top 5, el motor tiene en cuenta cuántas veces recomendó recientemente a cada grupo
frente a su cupo restante. Entre combinaciones casi igual de buenas para el estudiante, prefiere
las que menos gente está mirando. Es la opción C del [ADR 0011](0011-repartir-la-demanda-entre-grupos.md), que trata el escenario E4.

- **Gana:** menos estudiantes compiten por el mismo último cupo. Ataca la causa del problema y no
  solo el síntoma.
- **Paga:** las instancias necesitan un contador compartido de recomendaciones por grupo, que hoy
  no existe. La combinación 1 deja de ser la mejor según los criterios del estudiante, así que
  hay que explicarlo en pantalla.
- **Sacrifica:** usabilidad (lo recomendado no es exactamente lo preferido) y algo de desempeño
  (estado compartido entre instancias).
- **Decisión de producto:** pone el beneficio colectivo por encima de la preferencia individual.
  Favorece a quien consulta más tarde y perjudica a quien tiene las preferencias más comunes. La
  debe aprobar Registro.
- **Qué mediremos:** en una simulación con la distribución de S5, la proporción de estudiantes
  que encuentra disponible una de sus 5 combinaciones, frente a la diferencia de puntaje con su
  top 5 sin repartir.

### Resumen

| Opción | Gana | Paga | ¿Cumple la medida? |
|---|---|---|---|
| A · Revalidar antes de matricular | Atrapa lo que se llenó mientras decidía | Una solicitud y un paso más | En parte: no acelera los 5 s, pero evita la mayoría de los rechazos al matricular |
| B · Empujar los cambios a la pantalla | Se ve el cambio en menos de 1 s desde el motor | 4.000 conexiones y estado por conexión | Sí, si el SDK entrega en 4 s (ADR 0010) |
| C · Marcar los grupos en riesgo | Sin infraestructura nueva | Relega cupos reales | Depende: hay que medir los falsos llenos |
| D · Apartar el cupo | Lo elegido queda garantizado | Cambia el sistema académico y rompe el ADR 0002 | Sí, pero hoy no es viable (S4) |
| E · Repartir la demanda | Menos competencia por el mismo cupo | Contador compartido y combinación 1 no "ideal" | Depende: reduce el 2 %, no los 5 s; hay que simularlo |

Las opciones no se excluyen entre sí. A y B atacan la frescura en la pantalla, mientras que C y E
reducen la probabilidad de que el problema ocurra.

**Descartada de entrada:** consultar el cupo a la institución en cada solicitud. Contradice el
ADR 0002 y multiplicaría la carga sobre la base académica (ancla 4).

## Decisión

Abierta. Se elige en el Bloque 3, con las tácticas.
