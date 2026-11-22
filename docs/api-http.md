# API HTTP

La API que usa el portal de matrícula para pedir combinaciones, más las sondas de salud. Escucha
en `SCHED_HTTP_PORT` (por defecto `8080`) en las instancias con rol `query` o `all`. En el
despliegue con Docker se llega a ella a través de Traefik, en `localhost:8080`.

> **Provisional.** Hoy el contexto del estudiante llega en el cuerpo de la solicitud, sin firmar.
> Antes de exponer el motor a navegadores reales debe reemplazarse por un token firmado por la
> institución ([ADR 0004](adr/0004-contexto-del-estudiante-en-token-firmado.md)). Además, la
> forma de la solicitud cambiará cuando se agreguen más filtros del estudiante y las 5 mejores
> combinaciones ([ADR 0006](adr/0006-mejores-combinaciones-con-ramificacion-y-poda.md)).

Todos los nombres JSON van en `snake_case`.

## `POST /combinations`

Devuelve las combinaciones de grupos sin cruces de horario para los cursos habilitados del
estudiante. Cada combinación tiene exactamente un grupo por curso.

### Solicitud

```http
POST /combinations
Content-Type: application/json

{
  "student_id": "est-001",
  "eligible_courses": ["MAT101", "FIS101"],
  "avoid": [{ "day": "monday", "start_minute": 360, "end_minute": 480 }]
}
```

| Campo | Tipo | Obligatorio | Descripción |
|---|---|---|---|
| `student_id` | string | sí | Identificador del estudiante en la institución |
| `eligible_courses` | string[] | sí | Cursos que puede matricular. Los repetidos se ignoran |
| `avoid` | objeto[] | no | Ventanas que el estudiante prefiere evitar. Mismo formato que `slots` de la respuesta: `day` (`monday` … `sunday`, sin distinguir mayúsculas), `start_minute` y `end_minute` (`[inicio, fin)`, con `0 <= start_minute < end_minute <= 1440`). Ausente o vacío = sin preferencias |

`avoid` es una **restricción suave**: nunca descarta combinaciones, solo las ordena (ver
[Orden y penalización](#orden-y-penalización)).

### Respuesta `200`

```json
{
  "catalog_seq": 1,
  "stale": false,
  "total_credits": 7,
  "max_credits": 18,
  "schedules": [
    {
      "penalty": 0,
      "sections": [
        {
          "section_id": "MAT101-01",
          "course_id": "MAT101",
          "slots": [
            { "day": "monday", "start_minute": 360, "end_minute": 480 },
            { "day": "wednesday", "start_minute": 360, "end_minute": 480 }
          ]
        },
        {
          "section_id": "FIS101-02",
          "course_id": "FIS101",
          "slots": [{ "day": "friday", "start_minute": 480, "end_minute": 600 }]
        }
      ]
    }
  ]
}
```

| Campo | Descripción |
|---|---|
| `catalog_seq` | Versión del catálogo con que se calculó la respuesta. Es el mismo `seq` que asigna la institución, así que ambos lados saben a qué estado corresponde ([ADR 0003](adr/0003-replica-del-catalogo.md)) |
| `stale` | `true` si la réplica podría no estar al día. Ver [Cuándo `stale` es `true`](#cuándo-stale-es-true) |
| `total_credits` | Suma de créditos de los cursos pedidos (sin contar repetidos). Un curso sin créditos informados por la institución cuenta 0 |
| `max_credits` | Tope de créditos del periodo. `0` significa que no hay tope |
| `schedules` | Combinaciones compatibles; como máximo `SCHED_MAX_COMBINATIONS` (por defecto 200) |
| `schedules[].penalty` | Penalización de la combinación según `avoid`: número de franjas elegidas que caen en las ventanas a evitar. `0` si no hay `avoid` o no se incumple |
| `schedules[].sections` | Un grupo por cada curso pedido |
| `section_id`, `course_id` | Identificadores del grupo y del curso, tal como los envía la institución |
| `slots[].day` | `monday` … `sunday` |
| `slots[].start_minute`, `end_minute` | Minutos desde la medianoche. La franja es `[inicio, fin)`: 360–480 es de 06:00 a 08:00, y una clase que termina a las 08:00 no se cruza con otra que empieza a las 08:00 |

Reglas del cálculo:

- **Los créditos se verifican antes de buscar.** Dependen de los cursos y no de los grupos:
  si la suma de créditos de `eligible_courses` supera `max_credits`, no se hace la búsqueda y la
  respuesta es `422` (ver [Errores](#errores)). La suma igual al tope se acepta. El tope y los
  créditos de cada curso los define la institución en la carga completa
  ([contrato](contrato-institucion.md#créditos-y-reglas-del-periodo)).

- Solo se consideran grupos con **cupo disponible** (mayor que 0). Si un cambio deja un grupo en
  0, deja de aparecer en la siguiente respuesta.
- Si algún curso pedido **no tiene grupos con cupo** o no existe en el catálogo, no hay
  combinación completa posible y `schedules` llega vacío, con código `200`.
- Las combinaciones salen **de menor a mayor `penalty`**, con empate por ids de grupo. Ver
  [Orden y penalización](#orden-y-penalización).

### Orden y penalización

Las preferencias son restricciones suaves. Si E es el conjunto de ventanas de `avoid`, la
penalización de una combinación es p = Σ |franjas del grupo ∩ E|, sumada sobre sus grupos: cuenta
las franjas elegidas que caen en E. Una franja del grupo "cae" en E si se solapa con alguna
ventana de `avoid` (intervalos `[inicio, fin)`): una clase que termina justo cuando empieza la
ventana no cuenta, un solape parcial sí, y cada franja cuenta una sola vez aunque toque varias
ventanas.

Con `avoid` se devuelven las `SCHED_MAX_COMBINATIONS` combinaciones de **menor penalización de
todas las posibles** (búsqueda exacta), no las primeras encontradas. Sin `avoid` todas tienen
`penalty` 0 y el orden es el de ids de grupo.

### Errores

| Código | Cuándo | Cuerpo |
|---|---|---|
| `400` | El cuerpo no es JSON válido | JSON de error de Spring |
| `400` | Una ventana de `avoid` es inválida (día desconocido o ausente, `end_minute` no mayor que `start_minute`, fuera de `0..1440`) | `{ "error": "invalid_request", "message": "avoid[0]: ..." }` |
| `422` | Los créditos de los cursos pedidos superan el tope del periodo. No se buscó | `{ "error": "credit_limit_exceeded", "total_credits": 21, "max_credits": 18 }` |
| `503` | El motor aún no recibió ninguna carga completa del catálogo | `el catálogo aún no se ha cargado` (texto) |
| `503` | Un puerto de salida no está disponible | Mensaje del error (texto) |
| `500` | Error interno, incluido un historial inconsistente | Mensaje del error (texto) o JSON de Spring |
| `405` | Método distinto de `POST` | |

Limitaciones conocidas (por corregir):

- Si falta `student_id` o `eligible_courses` la respuesta es `500` y no `400`, porque todavía
  no se validan los campos.
- Con `eligible_courses` vacío la respuesta trae una combinación sin grupos
  (`"schedules": [{"sections": []}]`).

### Cuándo `stale` es `true`

La réplica se marca como desactualizada cuando ya no hay garantía de que refleje lo último de la
institución:

- Llegó un cambio con un hueco de `seq`, es decir, se perdió algún evento.
- Se cerró la conexión de cambios (`SyncChanges`) con la institución.

Vuelve a `false` con una carga completa nueva o cuando, tras reconectarse, llega el `seq`
siguiente sin huecos. Mientras tanto **el motor sigue respondiendo** con lo que tiene. El
portal debería avisar al estudiante que los cupos podrían no estar actualizados.

> Con el simulador es normal ver `stale: true` después de un `seats`: cada ejecución abre la
> conexión de cambios, envía un evento y la cierra, y ese cierre cuenta como pérdida de
> conexión. El SDK real mantendrá la conexión abierta.

### Ejemplo con curl

```sh
curl -X POST localhost:8080/combinations -H 'content-type: application/json' \
  -d '{"student_id":"est-001","eligible_courses":["MAT101","FIS101"]}'
```

## Sondas

| Endpoint | Responde `200` cuando | Si no | Lo usa |
|---|---|---|---|
| `GET /health` | El proceso está vivo | | Orquestador (liveness) |
| `GET /ready` | La réplica ya leyó todo el historial de NATS | `503` | Healthcheck de Docker, Traefik y readiness en Kubernetes |

`/ready` no indica si hay catálogo cargado: con el historial vacío la instancia está lista
(no tiene nada que leer), pero `/combinations` responde `503` hasta la primera carga. Ver
[ADR 0005](adr/0005-varias-instancias-con-historial-compartido.md).
