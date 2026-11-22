# API HTTP

La API que usa el portal de matrícula para pedir combinaciones, más las sondas de salud. Escucha
en `SCHED_HTTP_PORT` (por defecto `8080`) en las instancias con rol `query` o `all`. En el
despliegue con Docker se llega a ella a través de Traefik, en `localhost:8080`.

> **Nota.** La forma de la solicitud cambiará cuando se agreguen más filtros del estudiante y las
> 5 mejores combinaciones ([ADR 0006](adr/0006-mejores-combinaciones-con-ramificacion-y-poda.md)).

## Autenticación

Las solicitudes de consulta (`/combinations`, `/alternatives`) exigen un **token firmado por la
institución** ([ADR 0004](adr/0004-contexto-del-estudiante-en-token-firmado.md)) en
`Authorization: Bearer <token>`. `/health` y `/ready` no lo necesitan.

El token es un JWS compacto firmado con **EdDSA (Ed25519)**. El motor lo verifica con la llave
pública de la institución (`SCHED_JWT_PUBLIC_KEYS`); sin la firma válida no confía en nada de su
contenido.

| Parte | Valor |
|---|---|
| Cabecera | `alg` = `EdDSA` (exacto; cualquier otro, incluido `none`, se rechaza). `kid` = llave con que se firmó (obligatorio si hay más de una llave configurada; así se rota). `typ` opcional (`JWT`) |
| `iss` | Debe ser igual a `SCHED_JWT_ISSUER` |
| `aud` | Debe ser, o contener, `SCHED_JWT_AUDIENCE` |
| `sub` | **Identificador del estudiante** (`student_id`) |
| `courses` | **Cursos habilitados** del estudiante (`eligible_courses`): arreglo de ids |
| `iat`, `exp` | Obligatorios. `exp - iat` no puede superar `SCHED_JWT_MAX_TTL_S` (900 s por defecto) |
| `nbf` | Opcional |

Se admite una tolerancia de reloj de `SCHED_JWT_CLOCK_SKEW_S` (30 s por defecto) en `exp`, `nbf` e
`iat`. Un token de más de 8 KB se rechaza sin procesarlo. `sub` y `courses` siguen las mismas
reglas de ids y de cantidad de cursos que el cuerpo ([límites](#límites-de-la-solicitud)).

Con un token válido:

- `student_id` y `eligible_courses` **salen del token**; el cuerpo puede omitirlos. Si el cuerpo
  también los trae y **no coinciden** con el token (el mismo estudiante y el mismo conjunto de
  cursos), la respuesta es `400` en lugar de elegir uno en silencio.
- Las preferencias (`avoid`), `selection` y `enrolled` siguen viniendo en el cuerpo: no las firma
  la institución porque son elecciones del estudiante.

Sin token, con uno inválido, vencido o de otro emisor, la respuesta es siempre la misma, para no
dar pistas: `401` con `WWW-Authenticate: Bearer` y `{ "error": "unauthorized" }`. El motivo
(genérico) y el `kid` quedan en el log del motor; el token y su contenido, nunca.

Para desarrollo, `SCHED_AUTH=none` desactiva la verificación (el contexto vuelve a llegar en el
cuerpo, sin firmar) y el motor lo advierte en el log al arrancar. **No lo use fuera de desarrollo
ni de pruebas.** Para generar tokens de prueba: `keygen` y `token` del
[simulador](simulador.md#token-firmado-de-prueba).

Todos los nombres JSON van en `snake_case`.

## `POST /combinations`

Devuelve las combinaciones de grupos sin cruces de horario para los cursos habilitados del
estudiante. Cada combinación tiene exactamente un grupo por curso.

### Solicitud

```http
POST /combinations
Authorization: Bearer <token>
Content-Type: application/json

{
  "avoid": [{ "day": "monday", "start_minute": 360, "end_minute": 480 }]
}
```

(El estudiante y los cursos salen del token. Con `SCHED_AUTH=none` el cuerpo lleva además
`student_id` y `eligible_courses`.)

| Campo | Tipo | Obligatorio | Descripción |
|---|---|---|---|
| `student_id` | string | solo con `SCHED_AUTH=none` | Identificador del estudiante en la institución. Con token sale de `sub` |
| `eligible_courses` | string[] | solo con `SCHED_AUTH=none` | Cursos que puede matricular. Los repetidos se ignoran. Con token salen de `courses` |
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
  "complete": true,
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
| `complete` | `true` si la búsqueda terminó y el resultado es exacto. `false` si venció el [tiempo máximo de búsqueda](#tiempo-máximo-de-búsqueda). Ver abajo |
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

### Tiempo máximo de búsqueda

Cada búsqueda tiene un tiempo máximo (`SCHED_SEARCH_TIMEOUT_MS`, por defecto 150 ms; `0` lo
desactiva; [ADR 0009](adr/0009-tiempo-maximo-de-la-busqueda.md)). Si vence antes de terminar, la
respuesta sigue siendo `200` con lo encontrado hasta ese momento y `"complete": false`:

- Cada combinación devuelta es válida (un grupo por curso, sin cruces).
- **No está garantizado** que sean las mejores según `avoid`, ni que lleguen al máximo pedido;
  puede incluso llegar `schedules` vacío aunque exista alguna combinación.
- Con `"complete": true` el resultado es exacto, igual que sin tiempo máximo.

El portal debería avisar al estudiante cuando `complete` es `false`: podría haber mejores
combinaciones.

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

### Límites de la solicitud

Acotan el trabajo que una sola solicitud puede pedir (la búsqueda crece de forma exponencial con
los cursos). Los valores por defecto son los de un pregrado y se cambian con variables de entorno
(ver [inicio rápido](inicio-rapido.md#configuración)):

| Límite | Por defecto | Variable | Aplica a |
|---|---|---|---|
| Cursos por solicitud | 12 | `SCHED_MAX_COURSES` | `eligible_courses`; también `selection` y `enrolled` de `/alternatives` |
| Ventanas a evitar | 42 | `SCHED_MAX_AVOID` | `avoid` |
| Longitud de un id | 64 | `SCHED_MAX_ID_LENGTH` | `student_id`, cursos y grupos |
| Tamaño del cuerpo | 65536 bytes (64 KiB) | `SCHED_MAX_BODY_BYTES` | Cuerpo HTTP completo |

Los ids (estudiante, curso y grupo) deben ser no vacíos y usar solo letras ASCII, dígitos, `-`,
`_` y `.`. Estar justo en el límite se acepta; excederlo es un `400` (o `413` para el cuerpo).

### Errores

| Código | Cuándo | Cuerpo |
|---|---|---|
| `401` | Falta el token, o es inválido, vencido, de otro emisor o audiencia, o supera los 8 KB. Ver [Autenticación](#autenticación) | `{ "error": "unauthorized" }` y `WWW-Authenticate: Bearer` |
| `400` | El cuerpo trae `student_id` o `eligible_courses` distintos a los del token | `{ "error": "invalid_request", "message": "student_id no coincide con el del token" }` |
| `400` | El cuerpo falta, no es JSON válido, o un campo tiene un tipo incorrecto (por ejemplo `eligible_courses` como texto) | `{ "error": "invalid_request", "message": "..." }` |
| `400` | Falta `student_id` o `eligible_courses`, o son nulos o vacíos; un id con caracteres no permitidos o demasiado largo; se excede un [límite](#límites-de-la-solicitud) | `{ "error": "invalid_request", "message": "falta student_id" }` |
| `400` | Una ventana de `avoid` es inválida (día desconocido o ausente, `end_minute` no mayor que `start_minute`, fuera de `0..1440`) | `{ "error": "invalid_request", "message": "avoid[0]: ..." }` |
| `413` | El cuerpo supera `SCHED_MAX_BODY_BYTES`. Se corta sin procesarlo | `{ "error": "payload_too_large", "message": "..." }` |
| `422` | Los créditos de los cursos pedidos superan el tope del periodo. No se buscó | `{ "error": "credit_limit_exceeded", "total_credits": 21, "max_credits": 18 }` |
| `503` | El motor aún no recibió ninguna carga completa del catálogo | `el catálogo aún no se ha cargado` (texto) |
| `503` | Un puerto de salida no está disponible | Mensaje del error (texto) |
| `500` | Error interno, incluido un historial inconsistente | Mensaje del error (texto) o JSON de Spring |
| `405` | Método distinto de `POST` | |

Un cuerpo malo (campo ausente, tipo incorrecto, JSON roto) nunca es un `500`.

Limitación conocida (por corregir): con `eligible_courses` vacío la respuesta trae una
combinación sin grupos (`"schedules": [{"sections": []}]`).

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
# TOKEN: ver docs/inicio-rapido.md (la institución lo firma; en desarrollo lo genera el simulador)
curl -X POST localhost:8080/combinations -H "Authorization: Bearer $TOKEN" \
  -H 'content-type: application/json' -d '{}'
```

## `POST /alternatives`

Cuando un grupo de la selección del estudiante se quedó sin cupo, o la selección tiene un cruce,
propone combinaciones que **conservan la mayor cantidad posible de sus elecciones** y cambian
solo lo necesario, filtradas por cupo. Cada cambio indica qué conflicto resuelve.

### Solicitud

```http
POST /alternatives
Authorization: Bearer <token>
Content-Type: application/json

{
  "avoid": [{ "day": "monday", "start_minute": 360, "end_minute": 480 }],
  "selection": { "MAT101": "MAT101-01", "FIS101": "FIS101-02" },
  "enrolled": ["MAT101-01"]
}
```

La autenticación y los campos son los de [`/combinations`](#solicitud) (el estudiante y los cursos
salen del token; con `SCHED_AUTH=none` el cuerpo trae también `student_id` y `eligible_courses`) más:

| Campo | Tipo | Obligatorio | Descripción |
|---|---|---|---|
| `selection` | objeto | no | Selección actual: `{ "<curso>": "<grupo>" }`. Puede no cubrir todos los cursos. Ausente = sin elecciones |
| `enrolled` | lista de grupos | no | Grupos de la `selection` que el estudiante **ya tiene inscritos**. Cuentan como con cupo aunque la réplica marque 0 (quizá el último cupo lo tomó él), no generan `no_seats` y pueden conservarse con `distance` 0. Cada uno debe estar en `selection`, o es un `400`. Ausente = ninguno |

Un curso **sin elección** se elige libremente: aporta distancia 0 con cualquier grupo y aparece
en `changes` con razón `not_selected`. Una elección para un curso que no está en
`eligible_courses`, o un grupo que pertenece a otro curso, es un `400` (también un `enrolled` que no está en `selection`). Un grupo elegido que no
existe en el catálogo cuenta como sin cupo.

### Respuesta `200`

```json
{
  "catalog_seq": 1,
  "stale": false,
  "total_credits": 7,
  "max_credits": 18,
  "selection_valid": false,
  "complete": true,
  "conflicts": {
    "without_seats": [{ "course": "MAT101", "section": "MAT101-01" }],
    "clashes": []
  },
  "alternatives": [
    {
      "distance": 1,
      "penalty": 0,
      "changes": [
        { "course": "MAT101", "from": "MAT101-01", "to": "MAT101-02", "reason": "no_seats" }
      ],
      "sections": [
        {
          "section_id": "MAT101-02",
          "course_id": "MAT101",
          "slots": [{ "day": "tuesday", "start_minute": 480, "end_minute": 600 }]
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
| `catalog_seq`, `stale`, `total_credits`, `max_credits` | Igual que en `/combinations` |
| `complete` | `true` si la búsqueda terminó y las alternativas son exactas; `false` si venció el [tiempo máximo de búsqueda](#tiempo-máximo-de-búsqueda): las alternativas son válidas pero pueden no ser las mejores |
| `selection_valid` | `true` si la selección cubre todos los cursos, todos sus grupos tienen cupo y no se cruzan. Entonces la primera alternativa es la propia selección (`distance` 0) |
| `conflicts.without_seats` | Grupos de la selección sin cupo o inexistentes |
| `conflicts.clashes` | Pares `{first, second}` de grupos de la selección con cupo que se cruzan entre sí |
| `alternatives` | Como máximo `SCHED_MAX_ALTERNATIVES` (por defecto 5), en el orden descrito abajo. Vacío si no hay ninguna combinación completa posible |
| `alternatives[].distance` | Cursos con elección cuyo grupo difiere de la selección |
| `alternatives[].penalty` | Igual que `schedules[].penalty` de `/combinations` |
| `alternatives[].changes` | Un elemento por curso cuyo grupo difiere de la selección, con `from` (`null` si no había elección), `to` y `reason` |
| `alternatives[].sections` | Un grupo por curso pedido, en el mismo formato que `/combinations` |

Razones (`reason`):

| Valor | Significado |
|---|---|
| `no_seats` | El grupo elegido no tiene cupo o no existe |
| `clash` | El grupo elegido tiene cupo pero se cruza con otro grupo con cupo de la selección |
| `not_selected` | El curso no tenía elección (no suma a `distance`) |
| `rearranged` | El grupo elegido era válido; cambia para acomodar los cambios de otros cursos |

### Orden

Las alternativas se ordenan **lexicográficamente**: primero menor `distance` y, entre igual
distancia, menor `penalty`; los empates se resuelven por ids de grupo. No es una suma
ponderada: conservar los grupos elegidos tiene prioridad absoluta sobre `avoid`, así que una
alternativa de distancia 1 con penalización alta va antes que una de distancia 2 con
penalización 0. La búsqueda es exacta: son las mejores de todas las posibles.

### Errores

Los de [`/combinations`](#errores): `400` (cuerpo inválido, campos ausentes, límites excedidos,
`avoid` inválido, `selection` con un curso no pedido, un grupo de otro curso o un grupo vacío;
cuerpo `{ "error": "invalid_request", "message": "..." }`), `401`, `413`, `422` por créditos, `503` y
`500`. Los límites de `selection` y `enrolled` son los de [cursos](#límites-de-la-solicitud).

### Ejemplo con curl

```sh
curl -X POST localhost:8080/alternatives -H "Authorization: Bearer $TOKEN" \
  -H 'content-type: application/json' \
  -d '{"selection":{"MAT101":"MAT101-01","FIS101":"FIS101-02"}}'
```

## Sondas

| Endpoint | Responde `200` cuando | Si no | Lo usa |
|---|---|---|---|
| `GET /health` | El proceso está vivo | | Orquestador (liveness) |
| `GET /ready` | La réplica está al día: leyó el historial de NATS, tiene un catálogo cargado y no está `stale` | `503` | Healthcheck de Docker, Traefik y readiness en Kubernetes |

`/ready` refleja el estado **actual**, no solo el arranque. Responde `503` mientras no haya
catálogo cargado (historial vacío), si se pierde la sincronía (hueco de `seq`, cierre del
stream de cambios, caída de NATS en el follower) y mientras la instancia relee el historial.
Vuelve a `200` cuando se aplica una carga completa (o, si solo se cerró el stream sin hueco,
con el siguiente cambio en orden). Así el balanceador no envía tráfico a una réplica `stale`.
Ver [ADR 0005](adr/0005-varias-instancias-con-historial-compartido.md).
