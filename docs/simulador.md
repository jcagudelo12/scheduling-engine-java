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

## Comandos

Con Java (después de `./mvnw verify` o `./mvnw -q -pl simulation -am package -DskipTests`):

```sh
java -jar simulation/target/sched-simulation.jar simulate [escenario.json]
java -jar simulation/target/sched-simulation.jar load     [escenario.json] [seq]
java -jar simulation/target/sched-simulation.jar seats    <seq> <grupo> <cupos>
java -jar simulation/target/sched-simulation.jar experiment --config <json> --out <dir> [--seed n] [--profiles n]
java -jar simulation/target/sched-simulation.jar calibration --results <results.csv> [--calibration <json>]
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
  - Elige por jornada preferida con ruido, entre los grupos que veía con cupo al cargar la página
    (entre la carga y el envío otros pueden tomar el cupo).
  - Tras un rechazo prueba otro grupo de la misma asignatura. Con probabilidad
    `clashAwareness` nota a simple vista un cruce y descarta el grupo sin enviarlo.
  - Si agota los grupos con cupo por cruces: con probabilidad `deferOnExhaustProbability` la
    aplaza; si no, **retrocede**: retira un grupo ya inscrito que choque, reintenta la asignatura
    y deja la retirada para el final de la cola (evitando el grupo que retiró). Cada retiro y
    reintento cuenta y consume tiempo. Los retrocesos por asignatura y los envíos por estudiante
    están acotados, así que siempre termina.
  - Si la asignatura queda imposible (sin cupo en ningún grupo) la aplaza: cuenta como no
    matriculada.
- **B, asistida.** El estudiante pide al motor las combinaciones válidas (con cupo y sin cruces
  según la réplica) y elige una: la primera con probabilidad `pickBestProbability` y si no una al
  azar entre las primeras `pickTop`. Inscribe sus grupos; si al enviarlos la plataforma rechaza
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
(no hay una variable de rendimiento académico detrás). Dentro de cada ventana las llegadas son un
proceso de Poisson de `arrivals.arrivalsPerMinute`.

Para comparar el flujo A con los objetivos, `experiment` imprime al final las distribuciones
simuladas junto a las de la consulta, y `calibration --results results.csv` lo hace sobre un CSV
ya generado. Las categorías son las de la consulta:

- **A9**: veces que rehizo la selección = `r_traslape` (cada rechazo por cruce obliga a rehacer la
  elección de esa asignatura). Categorías 0, 1, 2, 3, 4, 5 o más.
- **A11**: `t_total_s` en minutos; <15 (excluyente), 15-30, 31-60, 1-2 h, >2 h (límites superiores
  incluyentes).
- **A13**: *sí* = al menos un rechazo por cupo en una asignatura que ya tenía un rechazo por cruce
  pendiente (`r_cupo_in_clash` > 0); *casi* = no perdió, pero inscribió un grupo con un cruce
  pendiente en la asignatura cuando quedaban `nearLossSeats` cupos o menos (`near_loss` > 0);
  *no* = ninguno. Se informa también A13b, donde *sí* es cualquier rechazo por cupo
  (`r_cupo` > 0), por si el criterio estricto resulta demasiado exigente.

### Parámetros

Todo está en el JSON de configuración; lo que falte toma el valor por defecto y un nombre
desconocido es un error. [`simulation/experiments/base.json`](../simulation/experiments/base.json)
trae todos los valores por defecto. **Todos son supuestos, no mediciones**, hasta que se calibren.

| Grupo | Parámetro | Defecto | Descripción |
|---|---|---|---|
| raíz | `seed`, `profiles` | 42, 500 | Semilla y número de perfiles (sobrescribibles con `--seed`, `--profiles`) |
| `offer` | `slackFactor` | 1,1 | Cupo total de la asignatura = demanda × factor |
| | `maxCredits` | 20 | Tope de créditos del periodo |
| | `popularSeatShare`, `seatUnevenness` | 0,7, 0,7 | Escasez de los grupos populares y desigualdad del reparto de cupos |
| | `minSections`, `maxSections`, `targetGroupSize` | 2, 6, 25 | Cuántos grupos tiene una asignatura |
| `population` | `preference` | mañana 0,40 / tarde 0,25 / noche 0,35 | Jornada preferida (supuesto) |
| `arrivals` | `windowStartSeconds`, `windowWeights` | 3 ventanas, un día entre sí, iguales | Ventanas de matrícula |
| | `arrivalsPerMinute` | 6 | Tasa de Poisson dentro de la ventana |
| `platform` | `seatEventLatencySeconds` | 2 | Latencia del evento de cupo hacia el motor |
| `engine` | `solver`, `maxResults`, `maxAlternatives` | forward-checking, 50, 5 | El motor que se evalúa |
| | `timeMode` | measured | `measured` suma el cómputo real; `zero` no |
| | `pickBestProbability`, `pickTop` | 0,7, 5 | Cómo elige el estudiante entre las combinaciones |
| `times` | `browseCourse` | 150 s, cv 0,6 | Navegar la oferta de una asignatura (A) |
| | `chooseAndSubmit` | 90 s, cv 0,6 | Elegir y enviar un grupo (A) |
| | `readRejection` | 60 s, cv 0,6 | Leer un rechazo o un mensaje del motor |
| | `withdraw` | 45 s, cv 0,5 | Retirar un grupo inscrito |
| | `submitChosen` | 20 s, cv 0,5 | Enviar un grupo ya decidido (B) |
| | `reviewCombinations`, `reviewAlternatives` | 120 s, 75 s, cv 0,6 | Revisar la lista del motor |
| | `confirm` | 90 s, cv 0,5 | Confirmar la matrícula |
| `behavior` | `preferenceStrength` | 2 | Peso de la jornada preferida al elegir grupo (0 = al azar) |
| | `clashAwareness` | 0,15 | Probabilidad de notar un cruce antes de enviar (A) |
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

### Limitaciones conocidas

- No hay búsqueda automática de parámetros: viene después. Los valores por
  defecto no están calibrados y el flujo A aún no reproduce A9, A11 ni A13.
