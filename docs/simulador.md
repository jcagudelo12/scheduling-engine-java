# Simulador e institución simulada

El módulo [`simulation`](../simulation/) tiene dos herramientas en un solo jar:

- **Simulador de escenarios** (`simulate`): corre un escenario completo en memoria, sin red ni
  NATS, con los mismos casos de uso del motor. Sirve para probar reglas y medir rápido.
- **Institución simulada** (`load`, `seats`): habla con un motor real por el
  [contrato gRPC](contrato-institucion.md), como lo hará el [SDK](sdk.md). Sirve para cargar
  datos y provocar cambios en un despliegue.

## Comandos

Con Java (después de `./mvnw verify` o `./mvnw -q -pl simulation -am package -DskipTests`):

```sh
java -jar simulation/target/sched-simulation.jar simulate [escenario.json]
java -jar simulation/target/sched-simulation.jar load     [escenario.json] [seq]
java -jar simulation/target/sched-simulation.jar seats    <seq> <grupo> <cupos>
```

Con Docker, contra el despliegue de [`deploy/`](../deploy/docker-compose.yml):

```sh
docker compose -f deploy/docker-compose.yml run --rm simulator load
docker compose -f deploy/docker-compose.yml run --rm simulator seats 2 MAT101-01 0
```

| Comando | Qué hace | Salida |
|---|---|---|
| `simulate` | Carga el escenario en un historial en memoria y calcula las combinaciones de cada estudiante | Tabla con combinaciones y tiempo por estudiante |
| `load` | Envía los grupos del escenario con `LoadCatalog`. Si no se indica `seq`, usa `catalog_seq` del escenario | `catálogo cargado: seq=1 grupos=7` |
| `seats` | Envía un `seats_changed` con `SyncChanges` y cierra la conexión | `respuesta del motor: ack seq=2` (o `resync_required` / `rejected`) |

Sin `escenario.json` se usa [`simulation/fixtures/sample.json`](../simulation/fixtures/sample.json).

**Variable de entorno:** `SCHED_INGEST_URL`, dirección de la ingesta. Por defecto
`localhost:50051`; en Docker Compose ya apunta a `ingest:50051`.

### Una sesión típica

```sh
java -jar simulation/target/sched-simulation.jar load                  # seq=1
java -jar simulation/target/sched-simulation.jar seats 2 MAT101-01 0   # ack seq=2: grupo lleno
java -jar simulation/target/sched-simulation.jar seats 2 MAT101-01 0   # ack seq=2: repetido, sin efecto
java -jar simulation/target/sched-simulation.jar seats 9 MAT101-01 5   # resync_required esperado=3 recibido=9
java -jar simulation/target/sched-simulation.jar load                  # vuelve a sincronizar (seq=1)
```

Después de cada paso puedes consultar `POST /combinations` y ver el efecto: el grupo lleno deja
de aparecer y, tras el hueco, las respuestas llegan con `stale: true`.

> Como `seats` cierra la conexión de cambios al terminar, las respuestas posteriores llevan
> `stale: true` hasta el siguiente cambio o carga. Es el comportamiento esperado: ver
> [api-http.md](api-http.md#cuándo-stale-es-true).

## Formato del escenario

```json
{
  "max_results": 200,
  "catalog_seq": 1,
  "students": [
    { "id": "est-001", "courses": ["MAT101", "FIS101"] }
  ],
  "sections": [
    {
      "id": "MAT101-01",
      "course": "MAT101",
      "capacity": 35,
      "available": 10,
      "slots": [
        { "day": "monday", "start": "06:00", "end": "08:00" },
        { "day": "wednesday", "start": "06:00", "end": "08:00" }
      ]
    }
  ]
}
```

| Campo | Obligatorio | Por defecto | Descripción |
|---|---|---|---|
| `max_results` | no | `200` | Máximo de combinaciones por estudiante (solo `simulate`) |
| `catalog_seq` | no | `0` | `seq` de la carga completa (lo usa `load` si no se pasa otro) |
| `students[].id` | sí, si hay estudiantes | | Identificador del estudiante |
| `students[].courses` | sí, si hay estudiantes | | Cursos habilitados (solo `simulate`) |
| `sections[].id` | sí | | Identificador del grupo |
| `sections[].course` | sí | | Curso del grupo |
| `sections[].capacity` | sí | | Cupo total |
| `sections[].available` | no | igual a `capacity` | Cupo disponible. Con `0` el grupo no se ofrece |
| `sections[].slots[].day` | sí | | `monday` … `sunday`, en minúsculas |
| `sections[].slots[].start`, `end` | sí | | Hora `HH:MM` en formato 24 h; `start` debe ser menor que `end` |

`load` solo usa `sections` y `catalog_seq`; `students` y `max_results` son para `simulate`.

## Crear escenarios propios

**A mano:** copia `sample.json` dentro de `simulation/fixtures/` y edítalo. Con Docker, esa
carpeta está montada en el contenedor, así que el archivo nuevo se usa sin reconstruir:

```sh
docker compose -f deploy/docker-compose.yml run --rm simulator load simulation/fixtures/mi-escenario.json
```

**Generado, de cualquier tamaño:** el generador de la prueba de carga crea un catálogo con
semilla fija (5 grupos por curso, alrededor del 10 % llenos):

```sh
python3 bench/generate_scenario.py 50 100        # 50 cursos → 250 grupos; 100 estudiantes
java -jar simulation/target/sched-simulation.jar load bench/scenario.json 20
#   catálogo cargado: seq=20 grupos=250
```

Los estudiantes generados quedan en `bench/students.json`, que usa k6, no en el escenario. Por
eso `simulate bench/scenario.json` no muestra estudiantes. Ambos archivos están en
`.gitignore`.

Para escenarios que deban quedar en el repositorio, por ejemplo como caso de prueba, guárdalos
en `simulation/fixtures/` con un nombre descriptivo.
