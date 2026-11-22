# Simulador e institución simulada

El módulo [`simulation`](../simulation/) tiene tres herramientas en un solo jar:

- **Simulador de escenarios** (`simulate`): corre un escenario completo en memoria, sin red ni
  NATS, con los mismos casos de uso del motor. Sirve para probar reglas y medir rápido.
- **Institución simulada** (`load`, `seats`): habla con un motor real por el
  [contrato gRPC](contrato-institucion.md), como lo hará el [SDK](sdk.md). Sirve para cargar
  datos y provocar cambios en un despliegue.
- **Experimento pareado** (`experiment`): simula una matrícula con muchos estudiantes sintéticos
  que compiten por los cupos, en dos condiciones (reactiva y asistida por el motor). Ver
  [Experimento pareado](#experimento-pareado-reactivo-frente-a-asistido).
- **Calibración y sensibilidad** (`calibrate`, `sensitivity`): ajustan el flujo A a la consulta y
  prueban cuánto resiste la conclusión a los supuestos. Ver [Calibración](#calibración) y
  [Sensibilidad](#sensibilidad).

## Comandos

Con Java (después de `./mvnw verify` o `./mvnw -q -pl simulation -am package -DskipTests`):

```sh
java -jar simulation/target/sched-simulation.jar simulate [escenario.json]
java -jar simulation/target/sched-simulation.jar load     [escenario.json] [seq]
java -jar simulation/target/sched-simulation.jar seats    <seq> <grupo> <cupos>
java -jar simulation/target/sched-simulation.jar demo-catalog --out <escenario.json> [--seed n]
java -jar simulation/target/sched-simulation.jar experiment --config <json> --out <dir> [--seed n] [--profiles n]
java -jar simulation/target/sched-simulation.jar calibration --results <results.csv> [--calibration <json>]
java -jar simulation/target/sched-simulation.jar calibrate --config <json> --out <dir> [--budget n] [--profiles n] [--seed n]
java -jar simulation/target/sched-simulation.jar sensitivity --config <json> --out <dir> [--profiles n] [--replications r]
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
| `demo-catalog` | `--out <escenario.json> [--seed n]`: exporta una oferta sintética estilo ITM (la del [experimento](#oferta-sintética-estilo-itm)) en el formato de escenario, con créditos y `max_credits`, para cargarla con `load` y probar el [cliente web](inicio-rapido.md#cliente-de-demostración) | Resumen y un estudiante de ejemplo con sus asignaturas |
| `keygen` | Genera un par de llaves Ed25519 para firmar tokens de prueba | `<kid>.key` (privada) y `<kid>.pem` (pública) |
| `token` | Imprime un token firmado para `Authorization: Bearer` | El JWT |

Sin `escenario.json` se usa [`simulation/fixtures/sample.json`](../simulation/fixtures/sample.json).

**Variable de entorno:** `SCHED_INGEST_URL`, dirección de la ingesta. Por defecto
`localhost:50051`; en Docker Compose ya apunta a `ingest:50051`.

### TLS con autenticación mutua

La ingesta exige mTLS, así que `load` y `seats` presentan un certificado de cliente y confían en
la CA del servidor. Se indican con opciones (que van en cualquier posición) o con variables:

| Opción | Variable | Qué es |
|---|---|---|
| `--tls-cert <pem>` | `SCHED_INGEST_TLS_CERT` | Certificado de cliente de la institución |
| `--tls-key <pem>` | `SCHED_INGEST_TLS_KEY` | Su llave privada (PKCS#8) |
| `--tls-ca <pem>` | `SCHED_INGEST_TLS_CA` | CA que firmó el certificado del servidor |
| `--plaintext` | `SCHED_INGEST_TLS=disabled` | Sin TLS; solo contra un motor con `SCHED_GRPC_TLS=disabled` (imprime un aviso) |

Sin ninguna de las dos formas, el comando falla con un mensaje que lo explica. En Docker Compose el
contenedor `simulator` ya trae las variables y monta `deploy/certs/client` (generado por
`deploy/setup-dev.sh`). Desde el host:

```sh
java -jar simulation/target/sched-simulation.jar load --tls-cert deploy/certs/client/client.crt \
  --tls-key deploy/certs/client/client.key --tls-ca deploy/certs/client/ca.crt
```

### Token firmado de prueba

Para probar la API HTTP con `SCHED_AUTH=jwt` ([ADR 0004](adr/0004-contexto-del-estudiante-en-token-firmado.md)):

```sh
# Un par de llaves; el motor carga la pública con SCHED_JWT_PUBLIC_KEYS=<directorio>
java -jar simulation/target/sched-simulation.jar keygen --out /tmp/sched-keys --kid dev-1

# Un token (iat = ahora, exp = ahora + ttl; ttl por defecto 600 s)
java -jar simulation/target/sched-simulation.jar token --key /tmp/sched-keys/dev-1.key --kid dev-1 \
  --student est-001 --courses MAT101,FIS101 --iss institucion-dev --aud sched-engine [--ttl 600]
```

Son herramientas de **desarrollo**: la llave privada vive en un archivo local. En producción
firma la institución con su propia llave.

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
  "max_credits": 12,
  "courses": [
    { "id": "MAT101", "credits": 4 },
    { "id": "FIS101", "credits": 3 }
  ],
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
| `max_credits` | no | `0` (sin tope) | Tope de créditos del periodo. Si los cursos de un estudiante lo superan, `simulate` termina con error (el motor real responde `422`) |
| `courses[].id`, `courses[].credits` | no | sin cursos: 0 créditos | Créditos de cada curso. Un curso sin entrada cuenta 0 |
| `students[].id` | sí, si hay estudiantes | | Identificador del estudiante |
| `students[].courses` | sí, si hay estudiantes | | Cursos habilitados (solo `simulate`) |
| `sections[].id` | sí | | Identificador del grupo |
| `sections[].course` | sí | | Curso del grupo |
| `sections[].capacity` | sí | | Cupo total |
| `sections[].available` | no | igual a `capacity` | Cupo disponible. Con `0` el grupo no se ofrece |
| `sections[].slots[].day` | sí | | `monday` … `sunday`, en minúsculas |
| `sections[].slots[].start`, `end` | sí | | Hora `HH:MM` en formato 24 h; `start` debe ser menor que `end` |

`load` usa `sections`, `courses`, `max_credits` y `catalog_seq`; `students` y `max_results` son para `simulate`.

## Crear escenarios propios

**A mano:** copia `sample.json` dentro de `simulation/fixtures/` y edítalo. Con Docker, esa
carpeta está montada en el contenedor, así que el archivo nuevo se usa sin reconstruir:

```sh
docker compose -f deploy/docker-compose.yml run --rm simulator load simulation/fixtures/mi-escenario.json
```

**Generado, de cualquier tamaño:** el generador de la prueba de carga crea un catálogo con
semilla fija (5 grupos por curso, alrededor del 10 % llenos, 2 a 4 créditos por curso y un
`max_credits` de 24, que ningún estudiante generado supera):

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

## Experimento pareado: reactivo frente a asistido

`experiment` mide si la matrícula asistida por el motor reduce el tiempo y los rechazos frente a
la plataforma actual. Es un diseño intra-sujeto por simulación: cada perfil sintético de
estudiante matricula en **las dos condiciones sobre la misma oferta y la misma secuencia de
llegadas**.

```sh
./mvnw -q -pl simulation -am package -DskipTests
java -jar simulation/target/sched-simulation.jar experiment \
    --config simulation/experiments/base.json --out resultados/base [--seed 7] [--profiles 500]
```

El código vive en `sched.simulation.experiment`. El simulador es "un adaptador más": en la
condición B usa los casos de uso reales del núcleo (`GenerateCombinations` y
`SuggestAlternatives`) sobre una réplica en memoria que se alimenta con los mismos casos de uso de
ingesta que usaría la plataforma real (`IngestCatalog` para la carga completa e
`IngestCatalogEvent` para los cambios de cupo, con secuencias consecutivas) a través del
historial en memoria. El núcleo evaluado es el que se despliega.

### Condiciones

- **A, reactiva (línea base).** Como la plataforma actual: por cada asignatura el estudiante carga
  la oferta, elige un grupo y solo al enviarlo la plataforma lo acepta o rechaza (por cruce con lo
  ya inscrito o por falta de cupo). Ensayo y error:
  - Elige por jornada preferida con ruido (y con más peso los grupos populares), entre los grupos
    que veía con cupo al cargar la página (entre la carga y el envío otros pueden tomar el cupo).
    Tras un rechazo por cupo el grupo queda marcado como lleno, pero con probabilidad
    `staleViewProbability` no recarga la página y sigue con los cupos viejos de los demás.
  - Tras un rechazo prueba otro grupo de la misma asignatura. Con la **conciencia de cruces** propia
    del estudiante nota a simple vista un cruce y descarta el grupo sin enviarlo. Esa conciencia no
    es igual para todos: es una mezcla (ver [Heterogeneidad](#heterogeneidad-entre-estudiantes)).
  - Si agota los grupos con cupo por cruces: con probabilidad `deferOnExhaustProbability` la
    aplaza; si no, **retrocede**: retira un grupo ya inscrito que choque, reintenta la asignatura
    y deja la retirada para el final de la cola (evitando el grupo que retiró). Cada retiro y
    reintento cuenta y consume tiempo. Los retrocesos por asignatura y los envíos por estudiante
    están acotados, así que siempre termina.
  - Si la asignatura queda imposible (sin cupo en ningún grupo) la aplaza: cuenta como no
    matriculada.
- **B, asistida.** El estudiante pide al motor las combinaciones válidas (con cupo y sin cruces
  según la réplica) y elige una: la primera con probabilidad `pickBestProbability` y si no una al
  azar entre las primeras `pickTop` (con más peso las de grupos populares). Revisa
    `combinationsReviewed` combinaciones (tope; si hay menos, las que haya) a
    `reviewCombination` por combinación. **Inscribe sus grupos uno por uno en la plataforma y paga lo
    mismo que A por hacerlo**: navegar la asignatura (`browseCourse`) y enviar el grupo
    (`submitSection`) por cada grupo; leer un rechazo, retirar y confirmar cuestan igual en A y en B.
    Solo son propios de B revisar combinaciones y revisar alternativas (`reviewAlternative`, hasta
    `alternativesReviewed` alternativas); solo es propio de A elegir el grupo (`chooseSection`).
    Si al enviarlos la plataforma rechaza
  uno (otro estudiante lo tomó mientras tanto), pide alternativas con su selección y **sigue con
  la primera**: retira los grupos inscritos que esa alternativa cambia (antes de inscribir los
  nuevos, para no cruzarse consigo mismo) y envía lo que falta.
  - Al pedir alternativas informa al motor qué grupos ya tiene inscritos (`enrolled`), para que
    no trate como sin cupo uno cuyo último cupo tomó él mismo. `GenerateCombinations` no necesita
    algo análogo: solo se llama antes de inscribir nada; desde que hay grupos inscritos se usan
    alternativas.
  - **Regla de abandono** cuando el motor no da combinaciones (o alternativas) con todas las
    asignaturas. Es equivalente a la de A, que solo aplaza lo realmente imposible, para que B no
    gane tiempo matriculando menos: (1) se quitan las asignaturas sin ningún grupo con cupo en la
    plataforma (las ya inscritas no cuentan); (2) si aún no hay combinación, se quita la
    asignatura cuya eliminación permite una (se prueba con el motor, de la última a la primera),
    prefiriendo la de menor prioridad (la última de la lista del perfil) entre las que lo logran;
    (3) si ninguna sola basta, se quita la última; y se repite. Esas consultas cuentan en
    `engine_calls` y en el tiempo; las quitadas cuentan como no matriculadas (y se retiran si ya
    estaban inscritas).
  - `maxAlternativeRounds` acota las alternativas adoptadas por estudiante.

### Plataforma simulada

Es la fuente de verdad de los cupos y el único validador de envíos, independiente del motor:
valida los cruces con su propio código (no usa el solver) y cuenta los rechazos. Valida primero el
cruce y luego el cupo. Al aceptar o retirar un grupo cambia el cupo y emite un
`seats_changed` hacia la réplica del motor **tras `seatEventLatencySeconds`** (2 s por defecto):
esa es la ventana real en que el motor puede sugerir un grupo que ya se llenó. Al terminar, el
experimento verifica que la réplica del motor quedó con los mismos cupos que la plataforma y que
ningún cupo es negativo ni excede la capacidad.

### Reloj, aleatoriedad y pareo

Simulación de eventos discretos: reloj simulado en segundos y cola de prioridad (los empates se
resuelven por orden de inserción). Todo es determinista con la semilla. Flujos de aleatoriedad
separados, derivados de `(semilla, nombre, índice)`: `offer`, `profiles`, `arrivals` y, por
estudiante, `behavior-time` y `behavior-choice` (derivados de la semilla y el id del estudiante).
Los perfiles y las llegadas se generan una vez y se usan en A y en B; el comportamiento puede
divergir entre condiciones, pero cada estudiante usa su propio generador, así que un cambio en un
estudiante no desordena a los demás.

El cómputo real del motor se mide con `nanoTime` y se **suma al tiempo simulado** del estudiante
(`engine.timeMode = measured`). Eso hace que dos corridas con la misma semilla difieran por la
variación del reloj de la máquina; con `engine.timeMode = zero` el cómputo no se suma y la corrida
es exactamente reproducible (así lo hacen las pruebas).

### Oferta sintética (estilo ITM)

Supuestos, todos parámetros de `offer`:

- Programas Ing. Mecatrónica, Ing. de Sistemas y "otros", con el peso de la consulta (A3);
  semestres 1 a 10; cada programa y semestre tiene `coursesPerSemester` asignaturas, de las cuales
  `sharedCoursesPerSemester` son comunes a todos los programas hasta `sharedUpToSemester`. Los
  compañeros de semestre piden las mismas asignaturas, así que hay cruces y competencia reales.
- Créditos de 2 a 4 por asignatura y tope `maxCredits` (20). Si el tope no deja cursar todas las
  asignaturas que el perfil quería, el perfil se queda con menos (`n_courses` en el CSV es lo que
  realmente pide; `nIntended` es lo que quería).
- Bloques de 2 h entre las 6:00 y las 22:00, de lunes a sábado; de 1 a 3 bloques por semana
  según los créditos (2 → 1 o 2, 3 → 2, 4 → 2 o 3), en días distintos.
- De 2 a 6 grupos por asignatura según su demanda (`targetGroupSize`). Cada grupo tiene una
  jornada (`sectionShift`) y sus bloques caen en ella con probabilidad `shiftAlignment`.
- Cupo total de la asignatura = demanda × `slackFactor` (1,1), con un mínimo por grupo, repartido
  de forma desigual (`seatUnevenness`) y con menos cupo en los grupos de la jornada más pedida
  (`popularSeatShare` < 1): son los "grupos populares" que se agotan primero.
- Cada grupo tiene una **popularidad** (lognormal de media 1 y dispersión `popularitySkew`, fija por
  grupo y semilla) que los estudiantes pesan al elegir sin que el cupo la refleje: los grupos más
  deseados se agotan primero. Cada asignatura tiene, con probabilidad `extraBlockProbability`, un
  bloque semanal adicional (práctica o laboratorio) sobre los que le tocan por créditos.
- Alternativa: `offer.file` apunta a un escenario JSON (formato de arriba) con una oferta real.
  Como no trae programa ni semestre, las asignaturas se reparten por orden de id en 10 semestres
  iguales, comunes a todos los programas.

### Perfiles y calibración

Los datos de la consulta a 135 estudiantes están en
[`consulta-2026-2.json`](../simulation/src/main/resources/calibration/consulta-2026-2.json) (con la
tabla del anexo de cada distribución). Con `calibrationFile` se puede usar otro archivo.

| Dato | Tabla | Uso |
|---|---|---|
| Programa | A3 | **Entrada**: programa de cada perfil |
| Semestre | A4 | **Entrada**: uniforme dentro del rango; "7 o más" se acota a 7-10 |
| Asignaturas a inscribir | A6 | **Entrada**: uniforme dentro del rango; "8 o más" = 8 |
| Veces que rehizo la selección por cruces | A9 | **Objetivo** del flujo A |
| Tiempo hasta confirmar | A11 | **Objetivo** del flujo A |
| Perdió o casi perdió cupo al resolver un cruce | A13 | **Objetivo** del flujo A |
| Qué hizo con la materia afectada | A14 | Referencia: 31,3 % "la aplazó" es el valor por defecto de `deferOnExhaustProbability` |

La **jornada preferida** (mañana, tarde, noche) no se preguntó en la consulta: es un supuesto
(`population.preference`). De ella se derivan las ventanas `avoid` que el estudiante manda al
motor en B (todo el día académico fuera de su jornada) y el peso de la elección de grupo en A. La
**ventana de matrícula** de cada perfil es otro supuesto: se sortea según `arrivals.windowWeights`
(no hay una variable de rendimiento académico detrás). Dentro de cada ventana, una fracción
`arrivals.rushShare` de los estudiantes entra en la avalancha de la apertura (llegadas exponenciales
desde el inicio de la ventana, de media `rushMeanSeconds`) y el resto llega como proceso de Poisson de
`arrivals.arrivalsPerMinute`.

Para comparar el flujo A con los objetivos, `experiment` imprime al final las distribuciones
simuladas junto a las de la consulta, y `calibration --results results.csv` lo hace sobre un CSV
ya generado. Las categorías son las de la consulta:

- **A9**: veces que rehizo la selección = `r_traslape` (cada rechazo por cruce obliga a rehacer la
  elección de esa asignatura). Categorías 0, 1, 2, 3, 4, 5 o más.
- **A11**: `t_total_s` en minutos; <15 (excluyente), 15-30, 31-60, 1-2 h, >2 h (límites superiores
  incluyentes).
- **A13**: la consulta no define cuándo empieza y termina "resolver un cruce". Se opera como el
  periodo entre el primer cruce que encuentra el estudiante (un rechazo de la plataforma o un cruce
  que nota él) y la confirmación. *Sí* = en ese periodo tuvo al menos un rechazo por cupo o
  encontró lleno un grupo que quería (se cruzaba) (`r_cupo_in_clash` > 0); *casi* = no perdió, pero
  inscribió un grupo en ese periodo cuando quedaban `nearLossSeats` cupos o menos
  (`near_loss` > 0); *no* = ninguno (incluye a quien nunca tuvo un cruce). Se informa también A13b,
  donde *sí* es cualquier rechazo por cupo (`r_cupo` > 0). Se probó antes una definición más
  estricta (solo en la asignatura con el cruce pendiente o en una retirada para resolverlo): con
  ella, dentro de cotas plausibles, el "sí" no pasaba de ~13 % frente a 39,3 %. La definición
  adoptada es una decisión de operacionalización y limita lo que se puede concluir de A13.

### Parámetros

Todo está en el JSON de configuración; lo que falte toma el valor por defecto y un nombre
desconocido es un error. [`simulation/experiments/base.json`](../simulation/experiments/base.json)
trae todos los valores por defecto. **Todos son supuestos, no mediciones**; los del flujo A y los
compartidos se calibran con la consulta en [`calibrated.json`](../simulation/experiments/calibrated.json)
(ver [Calibración](#calibración)). Los tiempos propios de B no tienen ningún dato empírico.

| Grupo | Parámetro | Defecto | Descripción |
|---|---|---|---|
| raíz | `seed`, `profiles` | 42, 500 | Semilla y número de perfiles (sobrescribibles con `--seed`, `--profiles`) |
| `offer` | `slackFactor` | 1,1 | Cupo total de la asignatura = demanda × factor |
| | `maxCredits` | 20 | Tope de créditos del periodo |
| | `popularSeatShare`, `seatUnevenness` | 0,7, 0,7 | Escasez de los grupos populares y desigualdad del reparto de cupos |
| | `popularitySkew`, `extraBlockProbability` | 0,5, 0,2 | Dispersión de la popularidad de los grupos (profesor, horario) y probabilidad de un bloque semanal adicional (laboratorio) |
| | `minSections`, `maxSections`, `targetGroupSize` | 2, 6, 25 | Cuántos grupos tiene una asignatura |
| `population` | `preference` | mañana 0,40 / tarde 0,25 / noche 0,35 | Jornada preferida (supuesto) |
| `arrivals` | `windowStartSeconds`, `windowWeights` | 3 ventanas, un día entre sí, iguales | Ventanas de matrícula |
| | `arrivalsPerMinute` | 6 | Tasa de Poisson dentro de la ventana |
| | `rushShare`, `rushMeanSeconds` | 0,3, 120 s | Fracción que entra en la avalancha de la apertura y su media |
| `platform` | `seatEventLatencySeconds` | 2 | Latencia del evento de cupo hacia el motor |
| `engine` | `solver`, `maxResults`, `maxAlternatives` | forward-checking, 50, 5 | El motor que se evalúa |
| | `timeMode` | measured | `measured` suma el cómputo real; `zero` no |
| | `pickBestProbability`, `pickTop` | 0,7, 5 | Cómo elige el estudiante entre las combinaciones |
| `times` | `browseCourse` | 150 s, cv 0,6 | Navegar la oferta de una asignatura (A: una vez por asignatura; B: por cada grupo que inscribe) |
| | `chooseSection` | 70 s, cv 0,6 | Decidir qué grupo enviar (solo A) |
| | `submitSection` | 20 s, cv 0,5 | Enviar (inscribir) un grupo (A y B) |
| | `readRejection` | 60 s, cv 0,6 | Leer un rechazo o un mensaje (A y B) |
| | `withdraw` | 45 s, cv 0,5 | Retirar un grupo inscrito (A y B) |
| | `confirm` | 90 s, cv 0,5 | Confirmar la matrícula (A y B) |
| | `reviewCombination`, `reviewAlternative` | 25 s, cv 0,6 | Revisar **una** combinación / alternativa del motor (solo B) |
| `behavior` | `preferenceStrength` | 2 | Peso de la jornada preferida al elegir grupo (0 = al azar) |
| | `clashAwareness`, `plannerShare`, `plannerAwareness`, `awarenessConcentration` | 0,15, 0,111, 0,9, 4 | Mezcla de la conciencia de cruces (A): media de quien hace ensayo y error, fracción que planeó antes (A10), su media y la concentración de la beta |
| | `speedCv` | 0,5 | Heterogeneidad de velocidad entre estudiantes (igual en A y en B) |
| | `staleViewProbability` | 0,3 | No recargar la página tras un rechazo (A) |
| | `combinationsReviewed`, `alternativesReviewed` | 5, 3 | Cuántas combinaciones / alternativas revisa B antes de elegir |
| | `assistedBrowseFactor` | 1 | Fracción de la navegación de A que B paga por grupo (1 = lo mismo; 0 = el motor lleva directo al grupo; solo para sensibilidad) |
| | `deferOnExhaustProbability` | 0,31 | Aplazar en vez de retroceder al agotar los grupos (A14) |
| | `maxRetreatsPerCourse`, `maxSubmissions` | 2, 60 | Cotas para que A termine siempre |
| | `maxAlternativeRounds` | 10 | Cota de alternativas adoptadas (B) |
| | `nearLossSeats` | 2 | Umbral de "casi perdió" |

Los tiempos son lognormales: se dan la media y el coeficiente de variación (`cv`); con `cv` 0 son
constantes.

### Salida

En `--out`:

- **`results.csv`**: una fila por perfil y condición (A y B). Columnas: `profile_id, program,
  semester, n_courses, window, preference, condition, t_total_s, r_traslape, r_cupo, r_total,
  courses_enrolled, courses_deferred, engine_calls, engine_ms_total`, más tres columnas de apoyo a
  la calibración (`withdrawals`, `r_cupo_in_clash`, `near_loss`).
  - `t_total_s`: segundos desde que inicia la selección hasta que confirma, con el cómputo real
    del motor sumado (`engine_ms_total`, en milisegundos).
  - `r_traslape`, `r_cupo`: rechazos por cruce y por cupo, **contados por la plataforma
    simulada**, no por el motor. En B `r_traslape` debe ser 0 por diseño.
- **`metadata.json`**: semilla, parámetros efectivos, resumen de la oferta, versión (`git
  describe`, o `desconocida`), fecha, versión de Java y los flujos de aleatoriedad.

Además imprime las medias por condición (incluida `courses_enrolled`) y la comparación con la
consulta. **Amenaza a la validez:** si la media de asignaturas matriculadas difiere entre A y B en
más de 0,1, la salida lo avisa y el análisis de Ttotal debe mostrarlo (un resultado más rápido no
debe pagarse con menos materias). `metadata.json` guarda las medias (`coursesEnrolledMean`).

**Verificación de corrección.** En B, cada combinación y cada alternativa que entrega el motor se
comprueba contra la plataforma (sin cruces y cubriendo las asignaturas pedidas); si alguna fallara,
el experimento termina con error en lugar de contarlo en silencio.

### Reproducir

```sh
git checkout <versión de metadata.json>
./mvnw -q -pl simulation -am package -DskipTests
java -jar simulation/target/sched-simulation.jar experiment \
    --config <la configuración> --out resultados/otra --seed <semilla de metadata.json>
```

Con `engine.timeMode = zero` los CSV son idénticos byte a byte; con `measured` coinciden los
perfiles, las llegadas y los conteos, y `t_total_s` varía por el reloj de la máquina.

### Análisis estadístico

Implementa la sección 3.3.4 del documento sobre el CSV del experimento. Se corre solo o encadenado:

```sh
java -jar simulation/target/sched-simulation.jar analyze --results resultados/base/results.csv \
    [--out resultados/base] [--alpha 0.05] [--mu0 0.20] [--seed 42]
# o, al terminar el experimento (usa la semilla del experimento para el bootstrap):
java -jar simulation/target/sched-simulation.jar experiment --config ... --out ... --analyze
```

Imprime un resumen y escribe en `--out` (por defecto, la carpeta del CSV) `analysis.md` (tablas
para el informe, punto decimal y 4 decimales) y `analysis.json` (todos los valores, incluidos los
parámetros y la semilla, para reproducirlo). Es determinista: misma entrada y semilla, mismos
resultados.

Qué calcula:

1. **Descriptivos** por condición (n, media, mediana, desviación estándar, rango intercuartílico,
   mín. y máx.) de `t_total_s`, `r_traslape`, `r_cupo`, `r_total` y `courses_enrolled`. Los cuantiles
   son de tipo 7 (el predeterminado de R).
2. **Hipótesis principal.** Por perfil, r = (Ttotal_A - Ttotal_B) / Ttotal_A. H0: la media de r es
   <= `mu0` (0,20) frente a H1: r > `mu0` (unilateral). Si Shapiro-Wilk (alfa) no rechaza la
   normalidad de r se usa la t de una muestra; si la rechaza, Wilcoxon de rangos con signo sobre
   r - `mu0`. Reporta estadístico, p, decisión, IC 95 % t de la media, IC 95 % bootstrap percentil de
   la media (10.000 remuestreos, semilla fija), la mediana de r y el tamaño del efecto (d de Cohen
   de una muestra con t; r = Z/raíz(n) con Wilcoxon). El reporte dice qué prueba se eligió y por qué,
   concluye en términos de la hipótesis y advierte que el p depende del número de perfiles.
3. **Rtotal** (B - A, pareado): misma regla de elección (normalidad de las diferencias); reporta la
   prueba bilateral y la unilateral (B < A), el IC 95 % t de la diferencia media y el tamaño del
   efecto (dz o r).
4. **Rtraslape en B** no se prueba: se verifica que sea 0 en todos los perfiles. Si no, el reporte
   marca **FALLA** y el comando termina con código 3.
5. **Amenaza a la validez:** diferencia B - A de `courses_enrolled` con su IC 95 %, y aviso si
   |dif| > 0,1. Los perfiles con Ttotal_A = 0 se excluyen de r y se cuentan.
6. **Subgrupos** (descriptivo, sin pruebas): reducción media y mediana por `n_courses`, `window` y
   `program`.

Decisiones de implementación:

- **Librería:** Apache Commons Statistics 1.3 (`commons-statistics-inference` y
  `-distribution`) para t, Wilcoxon y las distribuciones t y normal. Versión en el `pom.xml` raíz.
- **Shapiro-Wilk** no viene en Commons Statistics: `ShapiroWilk` implementa la aproximación de
  Royston (1995), algoritmo AS R94 (la de `shapiro.test` de R), válida para 3 <= n <= 5000; se
  contrasta con el valor de R en las pruebas. Con n > 5000 se usa una submuestra aleatoria de 5000
  con la semilla fija y el reporte lo indica. Una muestra constante (rango < 1e-19) no tiene W ni p:
  se trata como no normal y se usa Wilcoxon.
- **Wilcoxon:** `PValueMethod.AUTO` con corrección de continuidad: p exacto si n <= 50 sin empates ni
  ceros; si no, aproximación normal de Cureton, con corrección por empates (rangos medios) y por ceros
  (los valores iguales a `mu` se ordenan con los demás y se descuentan de la media y varianza de W+).
  El estadístico es W+. El Z del tamaño del efecto sale de la misma aproximación, sin corrección de
  continuidad. Si todas las diferencias son iguales a `mu`, p = 1 y efecto 0. Con Wilcoxon la prueba
  contrasta la ubicación de r - `mu0` (no exactamente la media).
- **Bootstrap:** remuestreo con reemplazo de tamaño n, `SplittableRandom` con la semilla dada, IC
  por los cuantiles 2,5 % y 97,5 % de las medias.

### Heterogeneidad entre estudiantes

Cada estudiante trae dos rasgos propios (sorteados una vez, de un flujo `behavior-trait`, así que son
los mismos en A y en B):

- **Conciencia de cruces**: la probabilidad de notar a simple vista un cruce antes de enviar. Es una
  mezcla de dos betas con la misma concentración: los que **planearon antes** (fracción
  `plannerShare`, media `plannerAwareness` = 0,9) y los de **ensayo y error** (media
  `clashAwareness`). El supuesto sale de la consulta: A10, el 11,1 % armó el horario antes en papel y
  el 71,9 % hizo ensayo y error; A12, el 34,1 % no preparó nada. La mezcla (y no un valor único)
  es lo que permite la cola pesada de A9 (17,8 % rehizo 5 o más veces): unos pocos estudiantes con
  conciencia casi nula y asignaturas de pocos grupos rehacen muchas veces.
- **Velocidad**: un factor lognormal de media 1 (`speedCv`) que multiplica todos sus tiempos
  humanos; da la cola pesada de A11 (1-2 h y más de 2 h).

## Calibración

`calibrate` busca los parámetros que acercan el flujo A a la consulta (A9, A11 y A13):

```sh
java -jar simulation/target/sched-simulation.jar calibrate \
    --config simulation/experiments/base.json --out simulation/experiments --budget 20000 --seed 42
```

Escribe `calibrated.json` (configuración completa, lista para `experiment`), `calibration.md` (tabla
consulta frente a simulado, distancias, parámetros con sus cotas y sensibilidad local) y
`calibration.json`. El resultado versionado está en
[`simulation/experiments/calibration.md`](../simulation/experiments/calibration.md).

**Método.** Es una búsqueda sobre 26 parámetros con cotas explícitas y justificadas (tiempos medios de
las acciones comunes y su cv, mezcla de conciencia de cruces, velocidad, escasez y popularidad de
cupos, estructura de la oferta, llegadas y avalancha, umbral de "casi perder"): hipercubo latino
(50 % del presupuesto) y luego Nelder-Mead desde los 12 mejores puntos, en el cubo unitario
normalizado con las cotas como restricción. Cada evaluación corre solo el flujo A (no usa el motor)
y se promedia sobre 4 semillas derivadas, **las mismas en todas las evaluaciones** (números
aleatorios comunes: las diferencias entre dos puntos se deben a los parámetros y no al azar); con una
sola semilla el ajuste se sobreajustaba a la oferta de esa semilla (se vio fuera de muestra). Las
evaluaciones son independientes y se paralelizan en un pool de hilos (no hay estado compartido entre
corridas: cada una arma su plataforma, su cola y su motor); el resultado es determinista y no depende
del orden en que terminan. Con 16 núcleos y 20.000 evaluaciones tarda unos 105 s; el presupuesto por
defecto (8.000) unos 50 s.

**Distancia.** Hellinger entre las distribuciones categóricas simulada y de la consulta, H(p, q) =
raíz(suma (raíz p - raíz q)^2 / 2), en [0, 1]; la total es la media de A9, A11 y A13 con pesos
iguales. Se prefirió a la chi-cuadrado porque está acotada, es simétrica, está definida con
categorías vacías (el flujo A produce a veces 0 % en categorías de cola) y es sensible a las
categorías pequeñas (4,4 % de "más de 2 h", 1,5 % de "4 veces"). La chi-cuadrado simétrica está
implementada (`Distance.chiSquareSymmetric`) para contrastar.

**Validación.** Se reporta la distancia dentro de muestra (las semillas de la calibración), fuera de
muestra (otro conjunto de semillas) y la media de 5 conjuntos nuevos. Como referencia de "qué tan
bien se puede quedar", el **piso de ruido** es la distancia esperada entre la propia distribución de
la consulta y una muestra de 135 estudiantes sacada de ella. **Sensibilidad local:** la distancia
total cuando cada parámetro se mueve ±10 % de su rango; un parámetro que la mueve menos de 0,005 se
reporta como **no identificable** (su valor es uno entre muchos igual de buenos, no una estimación).

**Resultado (semilla 42, 500 perfiles).**

| Tabla | Piso de ruido | Inicial (base.json) | Calibrada | Fuera de muestra | Veredicto |
|---|---|---|---|---|---|
| A9 | 0,069 | 0,320 | 0,237 | 0,235 | parcialmente reproducida (cola bien, pero 31 % sin cruces frente a 11,9 %) |
| A11 | 0,059 | 0,300 | 0,123 | 0,129 | parcialmente reproducida (queda corta la categoría "más de 2 h") |
| A13 | 0,039 | 0,345 | 0,209 | 0,213 | no reproducida ("sí" 14,9 % frente a 39,3 %; "casi" 33,6 % frente a 17,8 %) |
| **Total** | | 0,321 | 0,189 | 0,192 | |

Es un hallazgo de validez, no algo a esconder: **el flujo A calibrado reproduce A11 y la cola de A9,
pero no reproduce A13**, ni siquiera ampliando el modelo con mecanismos plausibles (heterogeneidad,
popularidad de grupos, avalancha de apertura, páginas sin recargar). La causa probable es que en el
modelo los cupos se agotan por la competencia entre los estudiantes simulados y eso produce pocos
rechazos por cupo durante la resolución de un cruce; en la realidad hay una competencia que el modelo
no ve. Por eso el A calibrado subestima la presión de cupos que sufre el estudiante, y esto debe
tenerse presente al interpretar los resultados de Rcupo.

**Limitaciones.** Con 26 parámetros y 11 grados de libertad en las tres tablas el problema está
sub-determinado: ajustar A9, A11 y A13 no prueba que cada parámetro sea correcto. Varios quedan
como no identificables en `calibration.md`. La calibración usa solo la marginal de tres tablas; no
ve la relación entre ellas (por ejemplo, entre el número de cruces y el tiempo). La consulta tiene
135 estudiantes y sus distribuciones tienen error de muestreo.

## Sensibilidad

`sensitivity` prueba cuánto resiste la conclusión principal (reducción de Ttotal frente a H0: r <=
20 %) a los supuestos del simulador, sobre todo a los tiempos de B, que no tienen datos empíricos:

```sh
java -jar simulation/target/sched-simulation.jar sensitivity \
    --config simulation/experiments/calibrated.json --out simulation/experiments/resultados --replications 10
```

Cada variación es un experimento pareado completo más el análisis de `analyze` (media de r, IC 95 %
bootstrap, p y decisión). Variaciones: tiempos compartidos x0,75 y x1,25; tiempos propios de B (revisar
combinaciones y alternativas) x2, x3 y x5, y B revisando el doble; B pagando la mitad o nada de la
navegación por grupo (cota optimista); menos y más holgura de cupos; latencia del evento de cupo 0, 2,
10 y 30 s; llegadas x0,5 y x2; B sin preferencias de jornada; y un caso pesimista combinado. Además:

- **Puntos de quiebre** por bisección: el factor sobre los tiempos propios de B con el que la
  reducción media cae al 20 % y la fracción de la navegación que B puede pagar antes de que caiga.
- **Réplicas**: la configuración calibrada con 10 semillas distintas (cada una genera su oferta,
  perfiles y llegadas): media y desviación estándar de la reducción media entre réplicas.
- **Tamaño de muestra**: potencia de la prueba principal con la desviación estándar observada de r y un
  efecto mínimo de interés de 5 puntos sobre el 20 % (aproximación normal unilateral con corrección de
  Guenther): también se agrega al análisis (`analysis.md`, sección 7).

Las corridas se paralelizan. Resultados versionados en
[`simulation/experiments/resultados/`](../simulation/experiments/resultados/).

**Resultado principal con A calibrado (500 perfiles, semilla 42).** Reducción media de Ttotal =
**10,8 %** (mediana 15,2 %; IC 95 % bootstrap [7,7 %; 13,9 %]); Wilcoxon (Shapiro-Wilk rechaza la
normalidad), p = 1,0000: **no se rechaza H0** (r <= 20 %). Rtraslape en B = 0 en todos los perfiles; Rtotal
baja de 2,14 a 0,60 (p < 0,0001). La reducción de 76 % de antes de calibrar era un artefacto: B no
pagaba navegar ni enviar cada grupo en la plataforma y A estaba sin calibrar.

**Sensibilidad.** La conclusión (no se rechaza H0) se mantiene en 17 de las 19 variaciones (todas menos
las dos que reducen la navegación de B): la media de r va de -21,8 % (tiempos propios de B x5) a 11,5 %. Lo que
decide la conclusión no son los tiempos propios de B sino cuánto de la navegación por grupo ahorra el
motor: la reducción cruza el 20 % cuando B paga el **83 %** de la navegación por grupo (ahorra al menos
el 17 %), y llega a 39,2 % con la mitad y a 67,2 % sin navegación. Los tiempos propios de B no tienen
margen: ya con x1 la reducción media (10,8 %) es menor que 20 %, y con x3 B es más lento que A. Latencia
del evento de cupo y tasa de llegadas casi no mueven r. Las réplicas dan una media de 12,6 % con
desviación estándar de 2,5 puntos (H0 no se rechaza en ninguna de las 10). La potencia: 429 perfiles
bastan para 0,90 con un efecto de 5 puntos; se usan 500.

**Amenaza a la validez adicional.** B matricula en promedio 0,21 asignaturas más que A (más de 0,1:
el análisis lo avisa), y cada asignatura extra cuesta navegar y enviar: la reducción de tiempo está
subestimada respecto de un resultado igual de completo.

**Limitaciones.** Las variaciones se hacen de a una (salvo el caso pesimista); no hay datos de B, así
que "pesimista" significa lo que el diseñador supone. El quiebre solo cubre los tiempos propios de B y
la navegación; no cubre errores de especificación del comportamiento (por ejemplo, un estudiante que
no confía en el motor y sigue probando por su cuenta). El cómputo real del motor se suma al tiempo
simulado y, con varias corridas en paralelo, puede inflarse levemente por contención de CPU.

### Limitaciones conocidas

- El flujo A calibrado no reproduce A13 y reproduce solo parcialmente A9 y A11 (ver
  [Calibración](#calibración)); las conclusiones sobre Rcupo y sobre Ttotal dependen de ese ajuste.
- B no tiene datos empíricos: los tiempos de revisar combinaciones y alternativas, el número de
  combinaciones que revisa y que el estudiante pague la navegación de cada grupo son supuestos.
- La oferta es sintética (o un escenario externo con `offer.file`); no representa una matrícula real.
