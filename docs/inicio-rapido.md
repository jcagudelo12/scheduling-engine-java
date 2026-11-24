# Inicio rápido

> **Términos técnicos.** Si alguno de estos términos es nuevo, cada enlace lo explica desde cero en [conceptos-base.md](conceptos-base.md), y el [glosario](glosario.md) traduce el vocabulario del dominio: [gRPC](conceptos-base.md#grpc-y-protocol-buffers) (llamadas remotas entre programas con mensajes binarios); [TLS/mTLS](conceptos-base.md#tls-mtls-certificados-ca-y-openssl) (conexión cifrada; en mTLS el servidor también exige certificado al cliente); [CA](conceptos-base.md#tls-mtls-certificados-ca-y-openssl) (autoridad que firma certificados); [PEM/PKCS#8/SAN](conceptos-base.md#tls-mtls-certificados-ca-y-openssl) (formato de texto de llaves y certificados, tipo de llave privada y lista de nombres válidos de un certificado); [JWT/Bearer](conceptos-base.md#jwt-y-bearer) (token firmado enviado en `Authorization: Bearer`); [EdDSA/Ed25519](conceptos-base.md#firma-eddsa-ed25519-y-llaves-pública-y-privada) (algoritmo de firma con llave pública y privada); [kid](conceptos-base.md#kid-el-identificador-de-la-llave) (nombre de la llave con que se firmó el token); [NATS/JetStream](conceptos-base.md#nats-y-jetstream) (mensajería que guarda en orden el historial del catálogo); [ADR](conceptos-base.md#adr-decisiones-de-arquitectura) (registro de una decisión de arquitectura); [JVM/JIT](conceptos-base.md#jit-y-calentamiento) (la máquina virtual de Java y su compilador que optimiza el código mientras corre); [p99/SLO](conceptos-base.md#prometheus-micrometer-y-métricas) (p99 es el valor bajo el cual está el 99 % de las mediciones; SLO es la meta de calidad fijada); [k6](conceptos-base.md#k6) (generador de carga HTTP); [Traefik](conceptos-base.md#traefik) (balanceador de carga); [Prometheus/Micrometer](conceptos-base.md#prometheus-micrometer-y-métricas) (recolector y librería de métricas); [Actuator](conceptos-base.md#actuator) (módulo de Spring Boot que publica estado y métricas); [ECS/MDC](conceptos-base.md#logs-y-formato-ecs) (estándar de campos de los logs en JSON y contexto de cada línea de log); [Docker/Compose](conceptos-base.md#docker) (contenedores y su orquestación local); [Maven](conceptos-base.md#maven-y-el-wrapper-mvnw) (herramienta de compilación y pruebas).

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

Los requisitos completos (qué instalar, cómo comprobarlo, Docker sin `sudo`, memoria y CPU) están en
[README.md](../README.md#requisitos-previos). En resumen:

| Camino | Necesita |
|---|---|
| A. Simulación en memoria | JDK 25 |
| B. Sistema completo con Docker | Docker 24+ con Compose v2, `openssl` y `curl`; el paso 5 además JDK 25 para firmar el token |
| C. Desarrollo local | JDK 25 y Docker (solo para NATS) |

Maven no hace falta: el wrapper `./mvnw` lo descarga. La primera compilación descarga las
dependencias y tarda unos minutos; las siguientes son mucho más rápidas. Si algo falla:
[solucion-de-problemas.md](solucion-de-problemas.md).

## A. Simulación en memoria

Corre un escenario de ejemplo con el mismo código del motor, pero con el historial en memoria
y sin abrir puertos.

```sh
./mvnw verify                                              # compila y corre las pruebas
java -jar simulation/target/sched-simulation.jar simulate
```

`simulate` calcula con el solver de **línea base** (`BacktrackingSolver`) y no con el
`forward-checking` que usa el motor por defecto, y sin tiempo máximo ni preferencias. Sirve para ver
el resultado de un escenario, no para medir cuánto tarda el motor real (para eso, el
[camino C](#c-desarrollo-local) o [`bench/`](../bench/README.md)).

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

# (--network host solo funciona en Linux; en Docker Desktop de Windows o macOS ver contrato-institucion.md#probar-el-contrato-a-mano)

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
SCHED_AUTH=none SCHED_GRPC_TLS=disabled java -jar server/target/sched-server-exec.jar

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
  SCHED_JWT_AUDIENCE=sched-engine SCHED_GRPC_TLS=disabled java -jar server/target/sched-server-exec.jar
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

## Cliente de demostración

El motor sirve un cliente web ligero en `/app/` (HTML, CSS y JavaScript sin framework ni
dependencias externas) para probar la API a mano: marcar franjas a evitar, ver las combinaciones
en una grilla semanal, elegir una, indicar grupos ya inscritos o sin cupo y ver las alternativas
con la explicación de cada cambio. Es un medio de prueba y demostración; el aporte principal es el
motor. `/app/**` se sirve **sin token** (son archivos estáticos sin datos de estudiantes); la API
sigue exigiéndolo.

```sh
# 1. Levantar el motor: desarrollo local sin token (camino C, pasos 1 y 2) o el compose (camino B)
docker run -d --rm --name sched-nats -p 127.0.0.1:4222:4222 nats:2 -js
./mvnw -q package -DskipTests
SCHED_AUTH=none SCHED_GRPC_TLS=disabled java -jar server/target/sched-server-exec.jar

# 2. En otra terminal: generar el catálogo de demostración (oferta sintética estilo ITM, con
#    créditos y tope) y cargarlo. La semilla (--seed, 42 por defecto) fija la oferta.
java -jar simulation/target/sched-simulation.jar demo-catalog --out simulation/fixtures/demo.json
#   catálogo de demostración: semilla=42 asignaturas=134 grupos=271 estudiantes=8 max_credits=20 -> ...
#   ejemplo para el cliente web: estudiante P0001, asignaturas ... (úsalos en el paso 3)
java -jar simulation/target/sched-simulation.jar load simulation/fixtures/demo.json --plaintext

# 3. Abrir http://localhost:8080/app/
```

Sin token (`SCHED_AUTH=none`), escribe un estudiante y las asignaturas que imprimió `demo-catalog`
(también están en el campo `students` del archivo). Con el compose (camino B, `SCHED_AUTH=jwt`),
carga el catálogo con el simulador del contenedor
(`docker compose -f deploy/docker-compose.yml run --rm simulator load simulation/fixtures/demo.json`),
firma un token con las asignaturas del ejemplo y pégalo en la página, que lo guarda solo en
`sessionStorage` y muestra sus asignaturas (sin confiar en ellas: el motor valida la firma):

```sh
TOKEN=$(java -jar simulation/target/sched-simulation.jar token \
  --key deploy/keys/private/dev-1.key --kid dev-1 --student P0001 --courses <asignaturas del ejemplo> \
  --iss institucion-dev --aud sched-engine)
```

Notas de uso:

- **Alertas de cupo.** "Este grupo se quedó sin cupo" pide alternativas de inmediato; para que el
  motor también vea el cupo en 0, envía el cambio con el simulador (la página muestra el comando
  exacto): `java -jar simulation/target/sched-simulation.jar seats <seq> <grupo> 0 --plaintext`.
- **Tiempo máximo ([escenario E5](calidad/04-escenarios.md#e5-el-motor-no-responde-a-tiempo-en-el-pico)).** Cada llamada espera 800 ms; si el motor no responde, la página
  avisa en menos de 1 s que continúes tu matrícula en la plataforma institucional, sin bloquearse.
  Para probarlo cambia el plazo: `http://localhost:8080/app/?timeout=1` (1 ms).
- **Soporte.** Cada llamada envía un `X-Request-Id` y la página lo muestra en los errores.
- **Pruebas de la lógica del cliente.** La lógica pura está en
  `adapters/gateway-http/src/main/resources/static/app/js/logic.js`. Con Node 22.7 o superior:
  `node --test adapters/gateway-http/src/test/js/`, o desde Maven
  `./mvnw -pl adapters/gateway-http -Pjs-tests test` (perfil opcional; la build normal no necesita Node).
- Las páginas llevan `Content-Security-Policy: default-src 'self'` (sin scripts ni estilos en
  línea), `X-Content-Type-Options: nosniff` y `Referrer-Policy: no-referrer`.

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
| `SCHED_WARMUP` | `on` | `on`: antes de responder `200` en `/ready`, la instancia calienta la JVM con consultas sintéticas en proceso (solo roles `query` y `all`; en `ingest` no hace nada). `off`: sin calentamiento. Otro valor impide arrancar. Ver [Calentamiento de la JVM](#calentamiento-de-la-jvm) |
| `SCHED_WARMUP_MIN_S` | `10` | Segundos mínimos de calentamiento: antes de esto no se evalúa si el compilador JIT ya se calmó. `0` o más y no mayor que el máximo |
| `SCHED_WARMUP_MAX_S` | `60` | Tope en segundos: a los 60 s la instancia se declara calentada aunque el JIT siga compilando. Al menos `1` |
| `SCHED_WARMUP_THREADS` | `0` | Hilos de calentamiento; `0` = uno por CPU disponible |

### JVM y dimensionamiento

La imagen arranca la JVM con el recolector Serial (`-XX:+UseSerialGC`) y hasta el 75 % de la memoria del contenedor: con una réplica de ~2 núcleos y un heap
pequeño gasta menos CPU por solicitud y tiene menos cola de latencia que G1 (ver [`bench/results/profile-server.md`](../bench/results/profile-server.md)). Para otro
recolector hay que redefinir el `ENTRYPOINT`: un `-XX:+UseG1GC` en `JAVA_TOOL_OPTIONS` choca con este y la JVM no arranca. **Con token** (verificar la firma
cuesta CPU), una réplica de 2 núcleos **ya caliente** sostiene 4.000-4.500 solicitudes/s con p99 de ~12-19 ms; **recién arrancada**
(JVM fría, `SCHED_WARMUP=off`) y con el pico encima, ~2.500/s. Sin token (solo desarrollo) la misma réplica sostiene ~5.000/s con p99 de ~3 ms.
Todas son cifras del perfil `itm` (3.475 grupos, 5 combinaciones por respuesta) medidas el 2026-11-22; en qué condiciones se midió cada una:
[`bench/README.md`](../bench/README.md#en-qué-condiciones-se-midió-cada-cifra). Con el calentamiento por defecto la réplica solo recibe tráfico
cuando ya está caliente (ver [Calentamiento de la JVM](#calentamiento-de-la-jvm)); aun así, ponga las réplicas en servicio antes de que abra la matrícula.

El simulador usa `SCHED_INGEST_URL` (por defecto `localhost:50051`) para saber dónde está la
ingesta. Como la ingesta exige mTLS, también necesita el certificado de cliente: `--tls-cert`,
`--tls-key` y `--tls-ca` (o `SCHED_INGEST_TLS_CERT`, `SCHED_INGEST_TLS_KEY` y `SCHED_INGEST_TLS_CA`),
o `--plaintext` (`SCHED_INGEST_TLS=disabled`) contra un motor con `SCHED_GRPC_TLS=disabled`. Ver
[simulador.md](simulador.md).

### Calentamiento de la JVM

**Qué problema resuelve.** Una JVM recién arrancada compila el código caliente con el JIT (C2) durante ~30 s y, en una réplica de 2 núcleos, eso
le quita capacidad justo cuando el balanceador le envía el pico de matrícula: las solicitudes se encolan y la latencia llega a segundos
([análisis](../bench/results/profile-server.md#por-qué-se-desborda-la-latencia-con-token)). Por eso `/ready` no responde `200` hasta que la JVM está caliente.

**Qué hace.** Solo en los roles `query` y `all` y con `SCHED_WARMUP=on` (por defecto). En cuanto la réplica tiene por primera vez un catálogo al día,
un hilo propio lanza `SCHED_WARMUP_THREADS` hilos (por defecto uno por CPU) que repiten, **en proceso y sin red**, el camino de una consulta con
entradas sintéticas (ids `warmup-N`, sin datos de estudiantes) generadas con semilla fija a partir de cursos y grupos reales del catálogo cargado:
4 a 7 cursos, con y sin `avoid`, y alternativas con `selection` y `enrolled` construidos desde grupos reales. Pasa por los casos de uso reales
(`GenerateCombinations`, `SuggestAlternatives`) con el mismo solver, tope de resultados y tiempo máximo, por los controladores y por el mismo
`JsonMapper` de Spring MVC. Con `SCHED_AUTH=jwt` también verifica firmas EdDSA, con un verificador **aparte** y una llave Ed25519 efímera (generada en
memoria, descartada al terminar, nunca registrada en el verificador real, ni en disco ni en logs): no abre ningún atajo de seguridad. No toca las
métricas `sched_search_*` ni `sched_http_*` (usa `EngineMetrics.NOOP` y no pasa por los filtros HTTP); lo suyo está en `sched_warmup_duration_seconds` y
`sched_warmup_searches`.

**Cuándo termina.** Cada segundo mide cuánto tiempo de compilación JIT se acumuló (`CompilationMXBean`). Termina cuando, pasados `SCHED_WARMUP_MIN_S`,
**3 ventanas seguidas** compilan menos de 30 ms, o a los `SCHED_WARMUP_MAX_S` pase lo que pase. Medido en el entorno de la prueba (2 núcleos): 18-33 s
(el log lo dice: `calentamiento terminado: duración=… búsquedas=… motivo=…`). Si el calentamiento falla con una excepción, se registra y la instancia se
marca lista igualmente; nunca la deja `503` para siempre. Se hace **una sola vez por proceso**: si después la réplica queda `stale` y se recupera,
`/ready` vuelve a `200` sin calentar otra vez. Al apagar el contexto se interrumpe.

| Variable | Por defecto | Qué hace |
|---|---|---|
| `SCHED_WARMUP` | `on` | `on` u `off`. Otro valor impide arrancar |
| `SCHED_WARMUP_MIN_S` | `10` | Antes de esto no se mira si el JIT se calmó |
| `SCHED_WARMUP_MAX_S` | `60` | Tope duro |
| `SCHED_WARMUP_THREADS` | `0` | Hilos de calentamiento (`0` = uno por CPU disponible) |
| `SCHED_WARMUP_HTTP` | `off` | `on`: además pide `GET /health` a `localhost` (sin token, ruta pública de la sonda) para calentar Tomcat y Spring MVC. Mejora la latencia de los primeros 30 s pero alarga el calentamiento unos 25 s: ver [`bench/results/warmup.md`](../bench/results/warmup.md) |

**Efecto medido el 2026-11-23** (perfil `itm` de 3.475 grupos, 5 combinaciones por respuesta, 1 réplica de 2 núcleos fijados, recolector Serial, con token, 3.500 solicitudes/s con rampa de 20 s que empieza apenas `/ready` responde `200`,
3 corridas alternadas de cada una; detalle, 4.000/s, método y amenazas a la validez en [`bench/results/warmup.md`](../bench/results/warmup.md)):

| 3.500/s, con token | Sin calentamiento (`SCHED_WARMUP=off`) | Con calentamiento (por defecto) |
|---|--:|--:|
| Hasta `/ready` desde el arranque del proceso | 4,8 s (4,7-4,9) | 28,9 s (23,0-37,7) |
| p50 de los primeros 10 / 10-20 / 20-30 s | 296 / 275 / 113 ms | 1,1 / 0,6 / 0,5 ms |
| p99 de los primeros 10 / 10-20 / 20-30 s | 1.153 / 3.355 / 594 ms | 18 / 12 / 17 ms |
| Solicitudes que k6 no pudo lanzar a tiempo (cola) | 5.358 (2.083-8.516) | 0 |
| Errores (no 200 ni 422) | 0 | 0 |
| p50 / p99 en estado estable | 0,44 / 4,7 ms | 0,46 / 4,6 ms |

**Guía para Kubernetes.** El calentamiento hace que el pod tarde más en estar listo (~25-30 s con 2 núcleos, más con menos CPU) y que use todos sus núcleos mientras
tanto. Ajustes recomendados:

```yaml
containers:
  - name: engine
    readinessProbe:            # /ready: 503 hasta tener catálogo al día Y la JVM caliente; vuelve a 503 si la réplica queda stale
      httpGet: { path: /ready, port: 8080 }
      periodSeconds: 5
      failureThreshold: 2
    startupProbe:              # margen para arrancar + leer el historial + calentar (hasta SCHED_WARMUP_MAX_S = 60 s): 36 x 5 s = 180 s
      httpGet: { path: /ready, port: 8080 }
      periodSeconds: 5
      failureThreshold: 36
    livenessProbe:             # /health no depende del calentamiento ni del catálogo: no reinicie el pod por estar calentando
      httpGet: { path: /health, port: 8080 }
      periodSeconds: 10
      failureThreshold: 3
    lifecycle:
      preStop:
        exec: { command: ["sleep", "10"] }   # deja que el Service/Ingress retire el pod antes de cerrar (el motor termina las solicitudes en curso: server.shutdown=graceful)
    resources:
      requests: { cpu: "2", memory: 1Gi }
      limits: { cpu: "3", memory: 1Gi }      # no ajuste el límite de CPU al de la solicitud: el calentamiento y el JIT usan todos los núcleos y un límite justo los estrangula (throttling)
terminationGracePeriodSeconds: 60            # preStop (10 s) + cierre ordenado (hasta 30 s) + margen
strategy:
  type: RollingUpdate
  rollingUpdate: { maxUnavailable: 0, maxSurge: 1 }   # el pod nuevo debe estar listo (caliente) antes de retirar uno viejo
```

- Si el catálogo puede estar vacío al desplegar por primera vez (aún no se hizo la carga completa), `/ready` no pasa a `200` y una `startupProbe` sobre `/ready` reiniciaría
  el pod en bucle: use `/health` en la `startupProbe` en ese caso (la `readinessProbe` sigue controlando el tráfico).
- **HPA**: el pod nuevo tarda ~25-30 s en recibir tráfico (+ lo que tarde el nodo y la imagen), así que escale antes: umbral de CPU algo menor (p. ej. 50-60 % en vez
  de 70-80 %) y `behavior.scaleUp` sin ventana de estabilización larga. Escale **antes** de que abra la matrícula; el calentamiento no sustituye tener las
  réplicas listas para el pico.
- Con menos CPU por pod el JIT y el calentamiento tardan más (el máximo de 60 s lo acota); suba `SCHED_WARMUP_MAX_S` y la `startupProbe` si ve `motivo=tiempo máximo` en el log.
- Con Docker, la imagen declara `HEALTHCHECK … --start-period=90s`; con otro orquestador, dé al menos ese margen antes de contar `/ready` como fallo.

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

**Nivel de log.** Por defecto el motor registra desde `INFO`. Se cambia con variables de entorno
`LOGGING_LEVEL_<paquete>=<nivel>` (la forma estándar de Spring Boot: el punto del nombre del paquete
pasa a guion bajo y todo va en mayúsculas), con niveles `TRACE`, `DEBUG`, `INFO`, `WARN` y `ERROR`:

```sh
LOGGING_LEVEL_SCHED=DEBUG java -jar server/target/sched-server-exec.jar           # más detalle solo del código del motor (paquete sched)
LOGGING_LEVEL_ROOT=WARN LOGGING_LEVEL_SCHED_SERVER=INFO java -jar ...           # menos ruido: todo en WARN salvo sched.server (lo usa bench/)
LOGGING_LEVEL_IO_NETTY=WARN                                                      # silenciar un paquete concreto (aquí, Netty)
```

En el `docker-compose.yml`, las variables van en `environment:` del servicio. No subas
`org.springframework.web` a `DEBUG` en producción: registra cuerpos de solicitud
([política de logs](arquitectura.md#observabilidad)).

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
