# Inicio rápido

Tres formas de correr el motor, de la más simple a la más completa. Elige según lo que
necesites:

| Camino | Para qué sirve | Requiere |
|---|---|---|
| [A. Simulación en memoria](#a-simulación-en-memoria) | Ver el motor calcular combinaciones, sin red ni servicios | Java 25 |
| [B. Sistema completo con Docker](#b-sistema-completo-con-docker) | Ver todo funcionando como en producción: ingesta, varias réplicas y balanceador | Docker |
| [C. Desarrollo local](#c-desarrollo-local) | Cambiar código y probarlo rápido contra el motor real | Java 25 y Docker (solo para NATS) |

Todos los comandos se corren desde la raíz del repositorio. En Windows usa `mvnw.cmd` en lugar
de `./mvnw`, y Git Bash o WSL para los ejemplos con `curl`.

## Requisitos

| Herramienta | Versión | Cómo comprobarla |
|---|---|---|
| Java (JDK) | 25 | `java -version` |
| Docker con Compose | Docker 24+ y Compose v2 | `docker compose version` |
| Maven | No hace falta: el wrapper `./mvnw` lo descarga | |

La primera compilación descarga las dependencias y tarda unos minutos; las siguientes son
mucho más rápidas.

## A. Simulación en memoria

Corre un escenario de ejemplo con el mismo código del motor, pero con el historial en memoria
y sin abrir puertos.

```sh
./mvnw verify                                              # compila y corre las pruebas
java -jar simulation/target/sched-simulation.jar simulate
```

Salida esperada (los tiempos varían):

```
escenario: simulation/fixtures/sample.json
estudiante   combinaciones  tiempo (µs)
est-001                  3         1964
est-002                  2          111
est-003                  1          272
```

El escenario está en [`simulation/fixtures/sample.json`](../simulation/fixtures/sample.json).
Para crear otros, ver [simulador.md](simulador.md).

## B. Sistema completo con Docker

Levanta lo mismo que en un despliegue real (ver [arquitectura.md](arquitectura.md)): NATS, una
instancia de ingesta, tres instancias de consulta y Traefik como balanceador, **con la seguridad
activada** ([Seguridad](arquitectura.md#seguridad)): TLS con autenticación mutua en la ingesta,
NATS con usuario y TLS, y token firmado en la API HTTP. Los secretos de desarrollo se generan con
un script y no se versionan. Hace falta `openssl` para el script; el simulador de institución
corre en un contenedor.

```sh
# 1. Generar los secretos de desarrollo: certificados (deploy/certs), llaves del token
#    (deploy/keys) y la contraseña de NATS (deploy/.env). Una sola vez.
deploy/setup-dev.sh

# 2. Construir y levantar (la primera vez tarda unos minutos)
docker compose -f deploy/docker-compose.yml up -d --build

# 3. Esperar a que todo esté "healthy"
docker compose -f deploy/docker-compose.yml ps

# 4. Cargar el catálogo de ejemplo, como lo haría la institución (con su certificado de cliente)
docker compose -f deploy/docker-compose.yml run --rm simulator load
#   catálogo cargado: seq=1 grupos=7

# 5. Firmar un token de prueba, como lo haría el backend de la institución al cargar la vista
#    del estudiante (necesita el jar del simulador: ./mvnw -q -pl simulation -am package -DskipTests)
TOKEN=$(java -jar simulation/target/sched-simulation.jar token \
  --key deploy/keys/private/dev-1.key --kid dev-1 --student est-001 --courses MAT101,FIS101 \
  --iss institucion-dev --aud sched-engine)

# 6. Pedir combinaciones como lo haría el navegador del estudiante
curl -X POST localhost:8080/combinations -H "Authorization: Bearer $TOKEN" \
  -H 'content-type: application/json' -d '{}'

# Sin token (o con uno inválido o vencido) el motor responde 401:
curl -i -X POST localhost:8080/combinations -H 'content-type: application/json' \
  -d '{"student_id":"est-001","eligible_courses":["MAT101"]}'
#   HTTP/1.1 401 Unauthorized
#   WWW-Authenticate: Bearer
#   {"error":"unauthorized"}
```

El estudiante (`sub`) y sus cursos habilitados (`courses`) salen del token; el cuerpo lleva solo
las preferencias (`avoid`), y `selection` y `enrolled` en `/alternatives`. El token también puede
generarse con cualquier librería JWT con EdDSA ([API HTTP](api-http.md#autenticación)). Vive 10
minutos por defecto (`--ttl`); cuando vence, genere otro.

La respuesta trae las combinaciones compatibles (aquí se muestra solo la primera de tres):

```json
{
  "catalog_seq": 1,
  "stale": false,
  "schedules": [
    {
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

Los campos se explican en [api-http.md](api-http.md).

### Qué más probar

```sh
# Agotar el cupo de un grupo (seq 2): la siguiente consulta ya no lo ofrece
docker compose -f deploy/docker-compose.yml run --rm simulator seats 2 MAT101-01 0
#   respuesta del motor: ack seq=2

# Ver los servicios gRPC de la ingesta (gracias a reflection, sin los .proto); hay que presentar
# el certificado de cliente, y un cliente sin él es rechazado
docker run --rm --network host -v "$PWD/deploy/certs/client:/certs:ro" fullstorydev/grpcurl \
  -cacert /certs/ca.crt -cert /certs/client.crt -key /certs/client.key localhost:50051 list

# Escalar a 5 réplicas de consulta; Traefik las toma solo cuando están listas
docker compose -f deploy/docker-compose.yml up -d --scale engine=5
```

- No hay panel de Traefik ni monitoreo de NATS expuestos en el host (el panel está desactivado y
  NATS solo es alcanzable dentro de la red de Docker). Para depurar NATS, publique
  `127.0.0.1:4222:4222` en `deploy/docker-compose.yml`, solo en esa interfaz.
- Traefik limita por IP las solicitudes por segundo (`RATE_LIMIT_AVERAGE`, `RATE_LIMIT_BURST`), las
  simultáneas (`INFLIGHT_MAX`) y el cuerpo (`MAX_BODY_BYTES`); responde `429` o `413` antes de
  llegar al motor. Los valores están en `deploy/.env`.

### Servicios del despliegue

| Servicio | Rol (`SCHED_ROLE`) | Puertos en el host |
|---|---|---|
| `nats` | JetStream con el historial del catálogo; usuario y contraseña, TLS y permisos mínimos | ninguno, solo red interna |
| `ingest` | `ingest`: recibe los datos de la institución (una sola instancia); solo con mTLS | gRPC `50051` (TLS con certificado de cliente obligatorio) |
| `engine` | `query`: atiende estudiantes con token firmado; escalable | ninguno, solo red interna |
| `lb` | Traefik frente a `engine`; solo enruta a réplicas `healthy`; límites por IP y de cuerpo | HTTP `8080` |
| `simulator` | Institución simulada (con certificado de cliente); solo con `run`, no arranca con `up` | ninguno |

### Apagar

```sh
docker compose -f deploy/docker-compose.yml down      # detiene; NATS conserva el historial
docker compose -f deploy/docker-compose.yml down -v   # además borra el historial (empezar de cero)
deploy/setup-dev.sh --force                           # (opcional) regenera certificados, llaves y contraseña
```

Si no borras el historial, al volver a levantar el sistema las réplicas recuperan el último
catálogo cargado sin necesidad de un nuevo `load`.

## C. Desarrollo local

El motor corre desde tu máquina en modo `all` (ingesta y consultas en el mismo proceso). Solo
NATS va en Docker, porque el motor siempre lo necesita para el historial del catálogo.

Este camino es para desarrollar rápido y **desactiva la seguridad** de forma explícita
(`SCHED_AUTH=none`, `SCHED_GRPC_TLS=disabled`, NATS sin autenticación). El motor lo advierte en
el log al arrancar. No lo use fuera de su máquina. Para probar con la seguridad activada, use el
[camino B](#b-sistema-completo-con-docker) o configure las variables de la
[tabla de seguridad](#seguridad).

```sh
# 1. NATS con JetStream (sin autenticación; solo en localhost)
docker run -d --rm --name sched-nats -p 127.0.0.1:4222:4222 nats:2 -js

# 2. Compilar y arrancar el motor (HTTP :8080, gRPC :50051)
./mvnw -q package -DskipTests
SCHED_AUTH=none SCHED_GRPC_TLS=disabled java -jar server/target/sched-server.jar

# 3. En otra terminal: cargar el catálogo y consultar
java -jar simulation/target/sched-simulation.jar load --plaintext
curl -X POST localhost:8080/combinations -H 'content-type: application/json' \
  -d '{"student_id":"est-001","eligible_courses":["MAT101","FIS101"]}'
```

Para probar el token en local, genere un par de llaves, arranque el motor con
`SCHED_AUTH=jwt` y firme un token:

```sh
java -jar simulation/target/sched-simulation.jar keygen --out /tmp/sched-keys --kid dev-1
SCHED_AUTH=jwt SCHED_JWT_PUBLIC_KEYS=/tmp/sched-keys SCHED_JWT_ISSUER=institucion-dev \
  SCHED_JWT_AUDIENCE=sched-engine SCHED_GRPC_TLS=disabled java -jar server/target/sched-server.jar
TOKEN=$(java -jar simulation/target/sched-simulation.jar token --key /tmp/sched-keys/dev-1.key \
  --kid dev-1 --student est-001 --courses MAT101,FIS101 --iss institucion-dev --aud sched-engine)
curl -X POST localhost:8080/combinations -H "Authorization: Bearer $TOKEN" \
  -H 'content-type: application/json' -d '{}'
```

Antes de cargar el catálogo el motor responde `503` con el mensaje `el catálogo aún no se ha cargado`. Es
lo esperado: todavía no tiene catálogo.

Para detener: `Ctrl+C` en el motor y `docker stop sched-nats`. Como ese contenedor no tiene
volumen, el historial se pierde y la próxima vez hay que volver a hacer `load`.

Al cambiar código, recompila el módulo y lo que depende de él. Por ejemplo, para el solver:
`./mvnw -q -pl server -am package -DskipTests`. Ver [CONTRIBUTING.md](../CONTRIBUTING.md).

## Configuración

El motor se configura con variables de entorno (ver
[`application.properties`](../server/src/main/resources/application.properties)):

| Variable | Por defecto | Qué hace |
|---|---|---|
| `SCHED_ROLE` | `all` | `ingest`, `query` o `all` ([ADR 0005](adr/0005-varias-instancias-con-historial-compartido.md)) |
| `SCHED_HTTP_PORT` | `8080` | Puerto HTTP (API del estudiante y sondas) |
| `SCHED_MANAGEMENT_PORT` | `8081` | Puerto de administración: `/actuator/prometheus` y `/actuator/health`, **sin token** y sin pasar por Traefik. Solo para la red interna; no lo publique |
| `SCHED_LOG_FORMAT` | `text` | `text` o `json` (un objeto JSON por línea, para un recolector de logs). Otro valor impide arrancar |
| `SCHED_GRPC_PORT` | `50051` | Puerto gRPC de ingesta (solo roles `ingest` y `all`) |
| `SCHED_NATS_URL` | `nats://localhost:4222` | Servidor NATS |
| `SCHED_NATS_STREAM` | `SCHED_CATALOG` | Stream de JetStream con el historial |
| `SCHED_NATS_SUBJECT_PREFIX` | `sched` | Prefijo de los sujetos de NATS |
| `SCHED_MAX_COMBINATIONS` | `200` | Combinaciones máximas por respuesta |
| `SCHED_MAX_ALTERNATIVES` | `5` | Alternativas máximas por respuesta de `POST /alternatives` |
| `SCHED_MAX_COURSES` | `12` | Cursos máximos por solicitud (también elementos de `selection` y `enrolled`); más es `400` |
| `SCHED_MAX_AVOID` | `42` | Ventanas `avoid` máximas por solicitud |
| `SCHED_MAX_ID_LENGTH` | `64` | Longitud máxima de un id (estudiante, curso, grupo) |
| `SCHED_MAX_BODY_BYTES` | `65536` | Tamaño máximo del cuerpo HTTP; más es `413` |
| `SCHED_SEARCH_TIMEOUT_MS` | `150` | Tiempo máximo de cada búsqueda en milisegundos; `0` lo desactiva. Al vencer, la respuesta trae `"complete": false` ([ADR 0009](adr/0009-tiempo-maximo-de-la-busqueda.md)) |
| `SCHED_SOLVER` | `forward-checking` | Algoritmo de búsqueda: `forward-checking` o `backtracking` (línea base) |

El simulador usa `SCHED_INGEST_URL` (por defecto `localhost:50051`) para saber dónde está la
ingesta. Como la ingesta exige mTLS, también necesita el certificado de cliente: `--tls-cert`,
`--tls-key` y `--tls-ca` (o `SCHED_INGEST_TLS_CERT`, `SCHED_INGEST_TLS_KEY` y `SCHED_INGEST_TLS_CA`),
o `--plaintext` (`SCHED_INGEST_TLS=disabled`) contra un motor con `SCHED_GRPC_TLS=disabled`. Ver
[simulador.md](simulador.md).

### Seguridad

Todas las variables son del motor y se leen en el arranque. **Los secretos nunca se registran en
el log.**

| Variable | Por defecto | Qué hace |
|---|---|---|
| `SCHED_AUTH` | `jwt` | `jwt`: exige un token firmado por la institución ([ADR 0004](adr/0004-contexto-del-estudiante-en-token-firmado.md)). `none`: sin autenticación, **solo desarrollo** (advierte al arrancar). Otro valor impide arrancar |
| `SCHED_JWT_PUBLIC_KEYS` | (ninguna) | Llaves públicas Ed25519 que verifican los tokens: lista separada por comas de `kid=ruta.pem`, de una ruta a un `.pem` (el `kid` es el nombre sin extensión) o de un directorio con `*.pem`. Con `jwt` y sin llaves, **no arranca**. Varias llaves permiten rotar |
| `SCHED_JWT_ISSUER` | (ninguno) | Valor exigido en `iss`. Obligatorio con `jwt` |
| `SCHED_JWT_AUDIENCE` | (ninguno) | Valor que debe figurar en `aud`. Obligatorio con `jwt` |
| `SCHED_JWT_CLOCK_SKEW_S` | `30` | Tolerancia de reloj, en segundos, para `exp`, `nbf` e `iat` |
| `SCHED_JWT_MAX_TTL_S` | `900` | Vida máxima de un token (`exp - iat`), en segundos |
| `SCHED_GRPC_TLS` | `required` | `required`: la ingesta gRPC exige TLS con autenticación mutua; sin los tres archivos de abajo **no arranca**. `disabled`: sin TLS, **solo desarrollo** (advierte al arrancar) |
| `SCHED_GRPC_TLS_CERT` | (ninguno) | PEM con el certificado del servidor de ingesta (y su cadena) |
| `SCHED_GRPC_TLS_KEY` | (ninguno) | PEM PKCS#8 con la llave privada del servidor |
| `SCHED_GRPC_TLS_CLIENT_CA` | (ninguno) | PEM con la CA que firma los certificados de cliente de la institución |
| `SCHED_NATS_USER`, `SCHED_NATS_PASSWORD` | (ninguno) | Credenciales del motor en NATS |
| `SCHED_NATS_TOKEN` | (ninguno) | Alternativa a usuario y contraseña (son excluyentes) |
| `SCHED_NATS_TLS_CA` | (ninguno) | PEM con la CA que firmó el certificado de NATS: activa TLS hacia NATS (use `SCHED_NATS_URL=tls://host:4222`) |

`deploy/setup-dev.sh` genera todo lo necesario para el despliegue de desarrollo.

### Métricas y logs

Cada instancia expone sus métricas en el puerto de administración (`SCHED_MANAGEMENT_PORT`, `8081`),
aparte del de los estudiantes y sin token. Con el motor en local (parte C):

```sh
curl -s localhost:8081/actuator/prometheus | grep '^sched_'   # métricas del motor
curl -s localhost:8081/actuator/health                         # {"status":"UP",...}
```

En el despliegue con Docker el puerto `8081` no se publica al host (Traefik tampoco lo enruta). Para
verlas hay un Prometheus opcional, en el perfil `observability`, que raspa cada instancia por la red
interna y queda solo en `127.0.0.1:9090`:

```sh
docker compose -f deploy/docker-compose.yml --profile observability up -d
# abrir http://localhost:9090 y probar, por ejemplo:
#   sched:http_latency_seconds:p99           (p99 por endpoint, regla de deploy/prometheus/rules.yml)
#   sum(rate(sched_search_incomplete_total[5m]))   (búsquedas cortadas por el tiempo máximo)
#   sched_replica_stale                      (1 = réplica desactualizada)
# Alertas: http://localhost:9090/alerts
```

Qué mide cada métrica: [arquitectura.md](arquitectura.md#observabilidad).

Para logs en JSON (por ejemplo, para un recolector): `SCHED_LOG_FORMAT=json`. Cada solicitud acepta
la cabecera `X-Request-Id` (o se genera una) y la devuelve en la respuesta; el mismo id sale en las
líneas de log de esa solicitud (campo `request_id`), útil para seguir una consulta con problemas:

```sh
curl -si -H 'X-Request-Id: prueba-123' localhost:8080/health | grep -i x-request-id
```

Los logs **nunca** incluyen tokens, cuerpos de solicitud ni datos del estudiante
([política](arquitectura.md#observabilidad)).

## Si algo falla

Ver [solucion-de-problemas.md](solucion-de-problemas.md).
