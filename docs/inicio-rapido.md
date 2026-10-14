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
instancia de ingesta, tres instancias de consulta y Traefik como balanceador. No necesitas Java
instalado: el simulador de institución también corre en un contenedor.

```sh
# 1. Construir y levantar (la primera vez tarda unos minutos)
docker compose -f deploy/docker-compose.yml up -d --build

# 2. Esperar a que todo esté "healthy"
docker compose -f deploy/docker-compose.yml ps

# 3. Cargar el catálogo de ejemplo, como lo haría la institución
docker compose -f deploy/docker-compose.yml run --rm simulator load
#   catálogo cargado: seq=1 grupos=7

# 4. Pedir combinaciones como lo haría el navegador del estudiante
curl -X POST localhost:8080/combinations -H 'content-type: application/json' \
  -d '{"student_id":"est-001","eligible_courses":["MAT101","FIS101"]}'
```

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

# Ver los servicios gRPC de la ingesta (gracias a reflection, sin los .proto)
docker run --rm --network host fullstorydev/grpcurl -plaintext localhost:50051 list

# Escalar a 5 réplicas de consulta; Traefik las toma solo cuando están listas
docker compose -f deploy/docker-compose.yml up -d --scale engine=5
```

- Panel de Traefik (réplicas y rutas): <http://localhost:8090>
- Monitoreo de NATS: <http://localhost:8222>

### Servicios del despliegue

| Servicio | Rol (`SCHED_ROLE`) | Puertos en el host |
|---|---|---|
| `nats` | JetStream con el historial del catálogo | `4222`, monitoreo `8222` |
| `ingest` | `ingest`: recibe los datos de la institución (una sola instancia) | gRPC `50051` |
| `engine` | `query`: atiende estudiantes; escalable | ninguno, solo red interna |
| `lb` | Traefik frente a `engine`; solo enruta a réplicas `healthy` | HTTP `8080`, panel `8090` |
| `simulator` | Institución simulada; solo con `run`, no arranca con `up` | ninguno |

### Apagar

```sh
docker compose -f deploy/docker-compose.yml down      # detiene; NATS conserva el historial
docker compose -f deploy/docker-compose.yml down -v   # además borra el historial (empezar de cero)
```

Si no borras el historial, al volver a levantar el sistema las réplicas recuperan el último
catálogo cargado sin necesidad de un nuevo `load`.

## C. Desarrollo local

El motor corre desde tu máquina en modo `all` (ingesta y consultas en el mismo proceso). Solo
NATS va en Docker, porque el motor siempre lo necesita para el historial del catálogo.

```sh
# 1. NATS con JetStream
docker run -d --rm --name sched-nats -p 4222:4222 nats:2 -js

# 2. Compilar y arrancar el motor (HTTP :8080, gRPC :50051)
./mvnw -q package -DskipTests
java -jar server/target/sched-server.jar

# 3. En otra terminal: cargar el catálogo y consultar
java -jar simulation/target/sched-simulation.jar load
curl -X POST localhost:8080/combinations -H 'content-type: application/json' \
  -d '{"student_id":"est-001","eligible_courses":["MAT101","FIS101"]}'
```

Antes del paso 3 el motor responde `503` con el mensaje `el catálogo aún no se ha cargado`. Es
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
| `SCHED_GRPC_PORT` | `50051` | Puerto gRPC de ingesta (solo roles `ingest` y `all`) |
| `SCHED_NATS_URL` | `nats://localhost:4222` | Servidor NATS |
| `SCHED_NATS_STREAM` | `SCHED_CATALOG` | Stream de JetStream con el historial |
| `SCHED_NATS_SUBJECT_PREFIX` | `sched` | Prefijo de los sujetos de NATS |
| `SCHED_MAX_COMBINATIONS` | `200` | Combinaciones máximas por respuesta |

El simulador usa `SCHED_INGEST_URL` (por defecto `localhost:50051`) para saber dónde está la
ingesta.

## Si algo falla

Ver [solucion-de-problemas.md](solucion-de-problemas.md).
