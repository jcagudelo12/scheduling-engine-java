# Contrato gRPC con la institución

Cómo entra la información de la universidad al motor. Este contrato lo implementará el
[SDK](sdk.md), y hoy lo implementa el simulador
([`FakeInstitution`](../simulation/src/main/java/sched/simulation/FakeInstitution.java)).

- **Definición:** [`proto/src/main/proto/institution/v1/`](../proto/src/main/proto/institution/v1/).
  Los `.proto` son la fuente de verdad; este documento explica cómo usarlos.
- **Dónde escucha:** la instancia con rol `ingest` o `all`, en `SCHED_GRPC_PORT` (por defecto
  `50051`). El puerto se abre solo cuando la réplica local está al día con el historial.
- **Sentido:** siempre de la institución hacia el motor. El motor nunca llama a la institución
  ([ADR 0002](adr/0002-roles-de-la-institucion.md)).
- **Seguridad:** TLS con **autenticación mutua** (mTLS) obligatorio por defecto. Ver
  [Seguridad: qué debe hacer la institución](#seguridad-qué-debe-hacer-la-institución).

> `engine/v1/catalog_log.proto` es un contrato **interno** entre instancias del motor (el
> historial en NATS). La institución no lo usa.

## Seguridad: qué debe hacer la institución

La ingesta lleva datos de la institución y solo debe aceptar a la institución. Por eso el motor
exige, por defecto (`SCHED_GRPC_TLS=required`), un canal cifrado con autenticación mutua, y el
contexto de cada estudiante viaja en un token que solo la institución puede firmar.

### 1. Canal gRPC: TLS con autenticación mutua

- La institución **genera su propia llave privada** y obtiene un **certificado de cliente**
  (`extendedKeyUsage = clientAuth`) firmado por la CA que se acuerde con quien opera el motor. La
  llave privada no sale de la institución.
- Quien opera el motor configura esa CA en `SCHED_GRPC_TLS_CLIENT_CA`: el servidor **rechaza**
  cualquier cliente sin certificado, o con uno firmado por otra CA.
- El motor presenta su certificado de servidor (`SCHED_GRPC_TLS_CERT` y `SCHED_GRPC_TLS_KEY`). La
  institución debe confiar en la CA que lo firmó y conectarse con un nombre que figure en el SAN
  del certificado (en desarrollo: `ingest` y `localhost`).
- El SDK debe usar los tres archivos PEM (certificado y llave de cliente, y la CA del servidor)
  con su cliente gRPC. Llaves en PKCS#8 (`BEGIN PRIVATE KEY`). Renueve los certificados antes de
  que venzan: un certificado vencido es rechazado.
- En desarrollo, `deploy/certs/generate.sh` (o `deploy/setup-dev.sh`) genera una CA y los
  certificados de servidor y de cliente. El simulador los usa con `--tls-cert`, `--tls-key` y
  `--tls-ca`.
- `SCHED_GRPC_TLS=disabled` quita el TLS y no autentica a nadie: solo para desarrollo, y el motor
  lo advierte al arrancar.

Se verifica con la prueba de integración
[`IngestGrpcMutualTlsTest`](../server/src/test/java/sched/server/IngestGrpcMutualTlsTest.java):
conecta quien tiene un certificado válido; falla el cliente sin certificado, el de otra CA y el
que no usa TLS.

### 2. Token del estudiante (API HTTP)

Este contrato gRPC no lleva datos de estudiantes. En la API HTTP, la institución firma un **JWT
con EdDSA (Ed25519)** cuando carga la vista del estudiante, y el navegador lo envía en
`Authorization: Bearer`. Lo que debe hacer:

- Generar un par Ed25519 y entregar al operador del motor **solo la llave pública** (PEM
  `BEGIN PUBLIC KEY`), con un `kid` que la identifique. Para rotar la llave, el operador
  configura las dos (`SCHED_JWT_PUBLIC_KEYS`), la institución empieza a firmar con la nueva y
  después se retira la anterior.
- Firmar tokens con `alg=EdDSA`, `kid`, `iss` (el acordado con el operador, `SCHED_JWT_ISSUER`),
  `aud` (`SCHED_JWT_AUDIENCE`), `sub` (id del estudiante), `courses` (sus cursos habilitados),
  `iat` y `exp`, con una vida corta (el motor rechaza más de `SCHED_JWT_MAX_TTL_S`, 15 minutos
  por defecto).
- Emitir un token nuevo cuando cambie la situación del estudiante.

Detalle de los claims y de las respuestas `401` en [api-http.md](api-http.md#autenticación).

### 3. Datos personales

El motor no guarda datos personales en disco: el catálogo y los cupos viven en una réplica en
memoria y en el historial de NATS (que no contiene estudiantes), y el contexto del estudiante
llega en cada solicitud y no se conserva. Los logs no registran el token ni su contenido.

## Servicios

| Servicio | RPC | Tipo | Para qué |
|---|---|---|---|
| `CatalogService` | `LoadCatalog(stream CatalogChunk) → LoadCatalogResponse` | streaming del cliente | Carga completa del catálogo |
| `CatalogEventsService` | `SyncChanges(stream CatalogEvent) → stream SyncStatus` | bidireccional | Cambios incrementales, con un acuse por evento |

### Datos

Un **grupo** (`Section`) es la unidad del catálogo:

| Campo | Descripción |
|---|---|
| `section_id` | Identificador único del grupo, por ejemplo `MAT101-01`. Obligatorio |
| `course_id` | Curso al que pertenece. Obligatorio |
| `slots` | Franjas semanales: `day` (`WEEKDAY_MONDAY` … `WEEKDAY_SUNDAY`), `start_minute` y `end_minute` en minutos desde la medianoche, con `0 ≤ inicio < fin ≤ 1440` |
| `capacity` | Cupo total |
| `available_seats` | Cupo disponible ahora. Con 0 el grupo no se ofrece a los estudiantes |

### Créditos y reglas del periodo

La carga completa (`CatalogChunk`) lleva, además de los grupos, los créditos de cada asignatura
y las reglas de matrícula del periodo. Se cargan una vez por periodo junto con la oferta, así
que cambiar el máximo de créditos no requiere tocar el motor: basta enviar una nueva carga.

| Campo del bloque | Descripción |
|---|---|
| `courses[]` (`Course`) | `course_id` (obligatorio) y `credits` (≥ 0). Los créditos dependen de la asignatura, no del grupo. Pueden repartirse en cualquier bloque. Un curso sin entrada cuenta 0 créditos |
| `rules` (`EnrollmentRules`) | `max_credits`: máximo de créditos que un estudiante puede matricular. **0 o ausente = sin tope**. Va una sola vez en toda la carga, en cualquier bloque (se recomienda el primero) |

Los campos son nuevos (números 3 y 4 de `CatalogChunk`), así que una carga sin ellos sigue siendo
válida y equivale a «sin créditos ni tope». Los eventos de `SyncChanges` no cambian créditos ni
reglas: para modificarlos hay que hacer una carga completa. El motor usa estos datos para
rechazar, antes de buscar, las solicitudes que superan el tope
([`422`](api-http.md#errores)).

## El número de secuencia `seq`

Todo lo que envía la institución lleva un `seq`, y es lo que permite al motor detectar si se
perdió algo ([ADR 0003](adr/0003-replica-del-catalogo.md)).

- **Lo asigna la institución** (es decir, el SDK) y debe crecer de uno en uno con cada cambio.
- Una **carga completa** fija el punto de partida: todos sus bloques llevan el mismo `seq`, y el
  primer cambio posterior debe llevar `seq + 1`. La carga se acepta con cualquier `seq`, incluso
  menor al actual, y reemplaza todo el catálogo.
- Cada **cambio** se aplica solo si su `seq` es exactamente el último aceptado más uno.
- Cada respuesta al estudiante incluye el `seq` con que se calculó (`catalog_seq`).

## Flujo

```mermaid
stateDiagram-v2
    [*] --> Carga: el SDK arranca
    Carga: LoadCatalog(seq = N)
    Sincronizado: SyncChanges abierto
    Carga --> Sincronizado: LoadCatalogResponse
    Sincronizado --> Sincronizado: CatalogEvent(seq = último + 1) → Ack
    Sincronizado --> Sincronizado: seq repetido → Ack (sin efecto)
    Sincronizado --> Carga: ResyncRequired
    Sincronizado --> Reconexion: se cae la conexión
    Reconexion: Reintento
    Reconexion --> Sincronizado: reabre SyncChanges y sigue con el siguiente seq
    Reconexion --> Carga: no sabe qué alcanzó a llegar
```

### 1. Carga completa: `LoadCatalog`

El cliente envía el catálogo en uno o varios bloques (`CatalogChunk`) y luego cierra el stream.
El motor responde una sola vez:

```json
{ "seq": "10", "sections_loaded": 2 }
```

| Error gRPC | Cuándo |
|---|---|
| `INVALID_ARGUMENT` | Bloques con `seq` distinto, un grupo sin `section_id` o `course_id`, una franja inválida, un curso sin `course_id` o con créditos fuera de rango, reglas repetidas en la carga, o una carga vacía. **La carga completa se descarta** |
| `INTERNAL` | No se pudo escribir en el historial, por ejemplo porque NATS no está disponible |

Partir en bloques evita mensajes enormes. Un tamaño razonable es de unos cientos de grupos por
bloque.

Se usa al conectarse por primera vez, cada vez que el motor responde `ResyncRequired` y, si se
quiere, periódicamente como red de seguridad.

### 2. Cambios: `SyncChanges`

Stream bidireccional que el SDK mantiene **abierto** mientras corre. Por cada `CatalogEvent` el
motor responde un `SyncStatus`, en el mismo orden.

| Cambio | Cuándo enviarlo |
|---|---|
| `seats_changed { section_id, available_seats }` | Cambió el cupo disponible de un grupo |
| `section_upserted { section }` | Grupo nuevo, o cambió su horario o su cupo total. Reemplaza el grupo entero |
| `section_removed { section_id }` | Se cerró o canceló el grupo |

Respuestas posibles:

| Respuesta | Significado | Qué debe hacer el SDK |
|---|---|---|
| `ack { seq }` | Aplicado, o ya estaba aplicado si el `seq` era repetido | Avanzar al siguiente |
| `resync_required { expected_seq, received_seq }` | Hay un hueco: se esperaba `expected_seq`. Con `expected_seq = 0`, el motor no tiene catálogo | Hacer una carga completa |
| `rejected { seq, reason }` | Datos inválidos (franja con fin antes del inicio) o fallo al escribir en el historial (por ejemplo, NATS no disponible) | Registrar el error. El `seq` **no se consume**: hay que corregir el dato y reenviar ese mismo `seq`, o hacer una carga completa |

Detalles a tener en cuenta:

- **El `seq` solo se consume tras escribir:** si el motor no pudo escribir el cambio en el
  historial, responde `rejected` y el `seq` queda libre; al reenviarlo se escribe de verdad y se
  responde `ack`. Un `ack` siempre significa que el cambio está en el historial.
- **Orden con varios flujos:** si el SDK reabre `SyncChanges` mientras el anterior sigue abierto,
  el motor serializa la validación y la escritura de todos los flujos, así que el historial
  queda en orden de `seq`. Aun así conviene cerrar el flujo anterior.
- **Idempotencia:** reenviar un `seq` ya aceptado responde `ack` y no cambia nada. Ante la duda
  tras una reconexión, el SDK puede reenviar lo último sin riesgo.
- **Grupo desconocido:** `seats_changed` sobre un grupo que el motor no tiene responde `ack` y
  avanza el `seq`, pero no crea el grupo, porque no tiene sus datos. Para un grupo nuevo se usa
  `section_upserted`.
- **Cerrar el stream** marca todas las réplicas como desactualizadas (`stale = true` en las
  respuestas a estudiantes) hasta que llega el siguiente `seq` o una carga completa (`/ready` pasa a `503` mientras tanto). Por eso el
  SDK debe mantenerlo abierto y reconectarse rápido si se cae.
- **Tras un hueco**, la ingesta rechaza todo cambio con `resync_required` hasta recibir una carga
  completa.

### Ejemplo de una sesión

```
LoadCatalog(seq=10, 2 grupos)                → seq=10 sections_loaded=2
SyncChanges:
  seq=11 seats_changed MAT101-01 → 11        → ack 11
  seq=11 seats_changed MAT101-01 → 11        → ack 11            (repetido: sin efecto)
  seq=12 section_removed FIS101-01           → ack 12
  seq=15 seats_changed MAT101-01 → 3         → resync_required esperado=13 recibido=15
LoadCatalog(seq=15, …)                       → vuelve a estar sincronizado
```

## Probar el contrato a mano

La ingesta tiene **gRPC Server Reflection** habilitado, así que las herramientas descubren los
servicios y mensajes sin necesidad de los `.proto`. Los ejemplos usan las imágenes de Docker
para no tener que instalar nada; `--network host` permite llegar a `localhost:50051`.

Primero levanta el motor ([inicio-rapido.md](inicio-rapido.md), camino B o C). Los ejemplos
presentan el certificado de cliente de desarrollo (`deploy/certs/client`, generado por
`deploy/setup-dev.sh`); contra un motor con `SCHED_GRPC_TLS=disabled` (camino C) sustituya esas
tres opciones por `-plaintext`.

### grpcui: interfaz web

```sh
docker run --rm --network host -v "$PWD/deploy/certs/client:/certs:ro" fullstorydev/grpcui \
  -cacert /certs/ca.crt -cert /certs/client.crt -key /certs/client.key -port 8081 localhost:50051
```

Abre <http://localhost:8081>. Ahí puedes elegir el servicio y el método, llenar el mensaje en un
formulario y ver la respuesta, como en Swagger UI.

### grpcurl: línea de comandos

```sh
G="docker run --rm -i --network host -v $PWD/deploy/certs/client:/certs:ro fullstorydev/grpcurl \
  -cacert /certs/ca.crt -cert /certs/client.crt -key /certs/client.key"

# Qué servicios hay y cómo son
$G localhost:50051 list
$G localhost:50051 describe institution.v1.CatalogEventsService
$G localhost:50051 describe institution.v1.Section

# Carga completa en dos bloques (cada objeto JSON es un mensaje del stream)
$G -d @ localhost:50051 institution.v1.CatalogService/LoadCatalog <<'EOF'
{"seq": 10, "sections": [
  {"section_id": "MAT101-01", "course_id": "MAT101", "capacity": 35, "available_seats": 12,
   "slots": [{"day": "WEEKDAY_MONDAY", "start_minute": 360, "end_minute": 480}]}
]}
{"seq": 10, "sections": [
  {"section_id": "FIS101-01", "course_id": "FIS101", "capacity": 30, "available_seats": 5,
   "slots": [{"day": "WEEKDAY_TUESDAY", "start_minute": 420, "end_minute": 540}]}
]}
EOF

# Cambios
$G -d @ localhost:50051 institution.v1.CatalogEventsService/SyncChanges <<'EOF'
{"seq": 11, "seats_changed": {"section_id": "MAT101-01", "available_seats": 11}}
{"seq": 12, "section_removed": {"section_id": "FIS101-01"}}
EOF
```

En JSON, los campos `uint64` como `seq` llegan entre comillas (`"seq": "11"`); es la
representación estándar de protobuf.

### Postman

Postman también soporta gRPC: *New → gRPC*, URL `localhost:50051` y, en *Service definition*,
*Use server reflection*. Con mTLS, configure en *Settings → Certificates* la CA y el certificado y
la llave de cliente para ese host.

## Generar un cliente en otro lenguaje

Con `protoc` o [`buf`](https://buf.build) se generan clientes desde
`proto/src/main/proto/institution/v1/` para cualquier lenguaje que soporte gRPC (Python, C#,
Go, Node.js, PHP…). Es útil para pruebas o integraciones puntuales; la vía prevista para las
universidades es el [SDK](sdk.md).
