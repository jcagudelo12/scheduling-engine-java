# Prueba del pico de matrícula (2026-11-22)

Mide E1 y E2 ([04-escenarios.md](../../docs/calidad/04-escenarios.md)) y la decisión del
[ADR 0009](../../docs/adr/0009-tiempo-maximo-de-la-busqueda.md) con la apertura de una ventana de
matrícula: rampa, meseta y descenso de la tasa de llegada, la mezcla de tráfico del portal y una ráfaga
de cupos concurrente. Cómo se corre y cómo se mide cada cosa: [`../README.md`](../README.md).
Las tablas salen de `bench/report_pico.py`; los datos crudos no se versionan.

## Entorno

| | |
|---|---|
| Fecha | 2026-11-22 02:09 |
| CPU | AMD Ryzen 7 2700 Eight-Core Processor (16 hilos) |
| Memoria | 30 GB |
| Kernel | 7.0.0-44-generic |
| Modo de frecuencia | schedutil |
| Docker | 29.1.3 |
| Imagen | f77df74dbb75 |
| Java | openjdk version "25.0.5.1" 2026-11-17 LTS |
| k6 | k6 v2.4.0 (commit/dd9395906d, go1.27.3, linux/amd64) |
| Traefik | 3.7.19 |
| Núcleos | motor 1 `4,6`, motor 2 `8,10`, motor 3 `12,14`, Traefik `0,1,2,3`, NATS e ingesta `5,7`, k6 `8-15` (1 réplica) o `5,7,9,11,13,15` (3), ráfaga `7` |
| Motor | 2 CPUs y 1 GB por réplica; SCHED_SEARCH_TIMEOUT_MS=150, SCHED_MAX_COMBINATIONS=5 |
| Camino de k6 | Traefik, con 2 núcleos (4 hilos) |
| Forma de la carga | rampa 25 s, meseta 45 s, descenso 5 s; ráfaga de cupos: 10 grupos/s en la meseta (450 grupos vaciados) |

Todas las corridas son de **una sola ejecución** por punto (no hay repeticiones): las diferencias de
pocos milisegundos entre puntos no son significativas, y se vio ruido grande cerca de la saturación
(ver 6.000/s directo). El procesador estaba en `schedutil`.

## Datos

Perfil `itm` de `generate_scenario.py` (semilla fija): 1.016 asignaturas en 24 programas x 9 semestres,
3.475 grupos (2 a 6 por asignatura, 8 en las 35 más demandadas; 9,4 % llenos al inicio), 29.755 estudiantes
con 1-3 asignaturas en 6,8 %, 4-5 en 70,4 %, 6-7 en 18,1 % y 8 en 4,6 %. **S5: 69,4 %** de los estudiantes
comparten 4 o más materias con su cohorte (programa y semestre), sobre el 60 % del supuesto. Mezcla: 80 %
`/combinations` (35 % con `avoid`, 0,5 % perfiles 7x8) y 20 % `/alternatives` (selección con un grupo lleno
o a punto de llenarse, y `enrolled`). Motor con `SCHED_MAX_COMBINATIONS=5` y `SCHED_SEARCH_TIMEOUT_MS=150`.
Sin token ni TLS, a propósito. 422 por créditos: 0,03 % de las solicitudes (respuesta válida, no cuenta como error).

## Resultado por tasa (meseta de 45 s)

"Traefik" = k6 entra por Traefik (2 núcleos); "directo" = k6 contra la réplica, sin proxy. CPU: 200 % = las 2 CPUs
del motor. `complete=false`: contado en las respuestas (k6) y, donde se pudo leer `/actuator/prometheus` al final de la corrida, en `sched_search_incomplete_total` (motor): coinciden, 0. (En las corridas `3 / 8.000 (*)` y `W 100` la lectura de Prometheus salió vacía; vale el conteo de k6.)

| Camino | Réplicas | Pico (sol/s) | Logradas | p50 (ms) | p95 (ms) | p99 (ms) | Errores | Descartadas | `complete=false` | CPU por motor | Cumple E2 |
|---|---|---|---|---|---|---|---|---|---|---|---|
| Traefik | 1 | 2.000 | 2.000 | 0,76 | 1,30 | 2,65 | 0 | 0 | 0 % | 81 % | sí |
| Traefik | 1 | 4.000 | 4.000 | 0,53 | 1,25 | 2,99 | 0 | 0 | 0 % | 104 % | sí |
| Traefik | 1 | 8.000 | 3.657 | 324 | 5.496 | 34.624 | 1,7 % | 254.123 | 0 % | 98 % | no (Traefik y k6 saturados) |
| directo | 1 | 5.000 | 5.000 | 0,30 | 1,22 | 2,97 | 0 | 0 | 0 % | 109 % | sí |
| directo | 1 | 6.000 | 5.828 / 6.000 | 0,34 / 0,30 | 124 / 2,95 | 320 / 16 | 0 | 42.711 / 6.624 | 0 % | 147 % / 137 % | no (2 corridas, borde) |
| directo | 1 | 8.000 | 8.000 | 0,81 | 48 | 113 | 0 | 19.519 | 0 % | 176 % | no |
| directo | 1 | 10.000 | 8.694 | 497 | 679 | 890 | 0 | 98.200 | 0 % | 192 % | no (saturado) |
| Traefik | 3 | 8.000 | 7.998 | 0,56 | 3,19 | **15** | 0 | 0 | 0 % | 93 % | sí (*) |
| Traefik | 3 | 8.000 | 7.983 | 0,66 | 68 | 416 | 0 | 17.186 | 0 % | 103 % | no (**) |
| Traefik | 3 | 12.000 | 7.365 | 25 | 2.301 | 5.672 | 0 | 291.075 | 0 % | 102 % | no (**) |

(*) k6 examina en detalle 1 de cada 30 respuestas de `/combinations` (`E1_SAMPLE=30`). (**) Con `E1_SAMPLE=3` (1 de cada 3,
como en el resto de la tabla) k6 consume demasiado CPU y el generador, no el motor, limita: las 3 réplicas están al 84-103 % de
sus 200 % mientras Traefik (222-348 % de 400 %) y k6 (367-379 % de 600 %, en hilos hermanos de los motores) saturan.

## E1: grupo que se llena durante el pico

Ráfaga de 10 grupos populares vaciados por segundo (450 en la meseta). La ingesta confirmó todos (`ack` p50 3-5 ms,
p99 10-16 ms; ninguno sin confirmar; retraso de la ráfaga <= 6 ms). Dos medidas del lado de k6:
combinaciones entregadas que contenían un grupo ya vaciado (E1: < 2 %) y una **sonda** (100 consultas/s de un curso cuyo grupo
se vació hace menos de 1 s): cuánto tarda el grupo en dejar de aparecer (E1: <= 5 s).

| Camino | Réplicas | Pico (sol/s) | Combinaciones examinadas | % con grupo lleno | Sonda: edad máx. a la que aún se ofreció (ms) | Sonda p99 (ms) | Edad máx. en tráfico (ms) |
|---|---|---|---|---|---|---|---|
| Traefik | 1 | 2.000 | 116.186 | 0,0009 % | 6 | 3,9 | 3 |
| Traefik | 1 | 4.000 | 232.395 | 0,0082 % | 5 | 4,0 | 12 |
| directo | 1 | 5.000 | 290.416 | 0,0048 % | 6 | 3,8 | 22 |
| Traefik | 3 | 8.000 (*) | 58.153 | 0,0086 % | 7 | 5,3 | 13 |
| directo | 1 | 6.000 (2.ª) | 348.572 | 0,0186 % | 119 | 18 | 125 |
| directo | 1 | 8.000 (saturado) | 464.855 | 0,0938 % | 772 | 665 | 714 |
| Traefik | 1 | 8.000 (colapso) | 209.184 | 1,77 % | 2.579 | 2.303 | 26.978 |
| directo | 1 | 10.000 (saturado) | 505.179 | 1,45 % | 1.601 | 1.551 | 4.631 |

Mientras el motor no está saturado el grupo deja de ofrecerse en **<= 7 ms** y menos del **0,02 %** de las combinaciones
lo contiene (E1 pide 5 s y 2 %). Con el motor saturado el seguidor se atrasa (el propio estado `stale` y el cupo tardan más):
1,5 % con grupo lleno a 10.000/s directo y 26 s de edad máxima en el colapso a 8.000/s por Traefik. Es decir, **E1 se cumple
en todas las tasas que cumplen E2 y se degrada junto con ella**. Las respuestas `stale` (0,1-0,15 %) son los últimos
instantes tras cerrar el stream de la ráfaga (`SyncLost`), no un defecto del motor.

## Peor caso 7x8, con `SCHED_SEARCH_TIMEOUT_MS=150`

Solo solicitudes 7 asignaturas x 8 grupos con `avoid` (que obliga a la búsqueda exacta), 1 réplica, sin ráfaga ni `/alternatives`.

| Perfil | Pico (sol/s) | p50 (ms) | p95 (ms) | p99 (ms) | Búsquedas | Cortadas (`complete=false`) |
|---|---|---|---|---|---|---|
| 7 de las 8-grupos, `avoid` de jornada variado | 50 | 1,77 | 4,42 | 6,11 | 1.021 | 0 (0 %) |
| ídem | 100 | 1,62 | 3,53 | 4,25 | 2.069 | 0 (0 %) |
| ídem | 200 | 1,52 | 2,67 | 3,60 | 4.083 | 0 (0 %) |
| 7 de las 8-grupos, `avoid` amplio (mañanas, noches, sábado) | 20 | 1,89 | 6,95 | 10 | 273 | 0 (0 %) |
| ídem | 50 | 1,75 | 4,72 | 7,93 | 743 | 0 (0 %) |
| 7 "W" de 8 grupos con 14 celdas libres para 14 sesiones (sintético) | 10 | 2,06 | 9,44 | 13 | 136 | 0 (0 %) |
| ídem | 100 | 2,29 | 3,22 | 4,74 | ~3.000 (k6) | 0 (0 %) |

**El tiempo máximo de 150 ms no se activó nunca**: 0 % de respuestas cortadas, tanto en el pico mixto (todas las
corridas, millones de búsquedas) como en el peor caso 7x8. La búsqueda de 7x8 tarda del orden de 1-2 ms (unos 250-430 nodos
por búsqueda). No logré generar un perfil 7x8 que el solver `forward-checking` no resuelva en milisegundos con ese catálogo;
el caso que sí lo supera es el de pigeonhole sin solución del ADR (12 asignaturas x 10 grupos), que no es un perfil real
de S7. Por tanto: **no se pudo medir la distancia del puntaje al top 5 exacto (no hubo cortes)** y el "99 % exactas" de E2 se
cumple con 100 % en estos datos.

## JMH, `SolverComparisonBenchmark` (`-wi 3 -i 5 -f 1 -w 2 -r 2`)

Comando: `java -jar benchmarks/target/benchmarks.jar SolverComparisonBenchmark -wi 3 -i 5 -f 1 -w 2 -r 2 -rf json -rff bench/results/jmh-solver-{antes,despues}.json`.
"Antes" se midió con el solver de `HEAD` (jar compilado antes de tocar `ForwardCheckingSolver` y `SlotBitmaps`); "después" con la optimización.
Los JSON crudos quedan en local (`bench/results/*.json` no se versiona; el comando de arriba los regenera). Tiempos en us por operación.

| Caso | `backtracking` | `forward-checking` antes | `forward-checking` después |
|---|---|---|---|
| 6 asignaturas x 5 grupos, hasta 500 resultados, sin costo | 59,5 | 414,6 | 71,9 |
| 7 x 8 sin solución (el último choca con todo) | 82.014 | 24,1 | 20,3 |
| Matrícula 7 x 6 (2 franjas de 120 min), 5 resultados, sin costo | 5,3 | 20,5 | 16,1 |
| ídem, 50 resultados | 17,0 | 44,0 | 26,3 |
| ídem, 500 resultados | 154,4 | 383,2 | 145,3 |
| Matrícula con costo (madrugar/trasnochar), 5 resultados | 10.289 | 29,9 | 25,3 |
| ídem, 50 resultados | 10.130 | 78,6 | 64,4 |
| ídem, 500 resultados | 10.194 | 744,4 | 524,3 |

Asignación del caso 6x5 con `-prof gc`: 166.178 B/op antes, 159.088 B/op después. Perfil (async-profiler, 6x5 antes): ~50 % del tiempo
en `compareIds` (comparación de `SectionId` como texto) y ~40 % en `record`/`PriorityQueue`, más la ordenación final; la preparación (streams, boxing)
fue ~2 %, así que no era el cuello de botella.

Conclusión: la optimización (rangos enteros por grupo en lugar de comparar texto, y lista sin cola de prioridad cuando no hay costo) deja a
`forward-checking` en el nivel de `backtracking` en los casos sin costo con muchos resultados (6x5: 72 vs 60 us, sigue ~20 % más lento;
matrícula con 500: igual), pero sigue siendo más lento con pocos resultados sin costo (5 resultados: 16 vs 5 us) porque paga la preparación
(conflictos, dominios). Con costo y en el caso sin solución es de lejos más rápido. Muestras cortas (5 iteraciones): úsese como orden de magnitud.

## Conclusiones

- **E2 se cumple**: p99 de 2,7-3,0 ms hasta 4.000/s por Traefik y 5.000/s directo con una réplica (límite: 200 ms), errores 0 %.
- **E1 se cumple**: un grupo vaciado deja de ofrecerse en <= 7 ms (límite 5 s) y < 0,02 % de las combinaciones lo contiene (límite 2 %), mientras el motor no esté saturado; con 30 matrículas/s el canal de cupos no se acerca a ser el límite (ack p99 < 17 ms). No se probaron 30 vaciados/s (se usó 10/s).
- **Capacidad de una réplica (2 CPUs, 1 GB)**: unas **5.000 solicitudes/s** de la mezcla del pico (p99 3 ms, CPU 109 %); a 6.000/s ya está en el borde (p99 16-320 ms según la corrida) y a 8.000/s se pasa de 100 ms. Cada solicitud cuesta ~0,25 ms de CPU de la réplica; la búsqueda es una parte pequeña (la de 6x5 mide decenas de us en JMH), el resto es HTTP/JSON/filtros (no se perfiló). Con Traefik de 2 núcleos delante, el proxy limita a ~4.000-5.000/s.
  - **Ojo: estas corridas fueron sin token** (`SCHED_AUTH=none`). Con JWT EdDSA la verificación de la firma costaba ~790 us de CPU por solicitud (70 % del total) y una réplica de 2 núcleos se saturaba cerca de 1.700/s. Tras usar Ed25519 de BouncyCastle y rechazar antes de la firma lo barato (ADR 0004), la firma cuesta ~210 us bajo carga (34 % del total) y una réplica de 2 núcleos sostiene **~2.000/s con token con p99 de ~6 ms** (a 3.000/s la p99 es de ~30 ms; sin token seguía sosteniendo ~5.000/s); ver [profile-server.md](profile-server.md#después-de-optimizar-el-token).
- **Capacidad de 3 réplicas**: sostienen **8.000/s con p99 de 15 ms** (CPU de cada motor 93 %). 12.000/s no se logró, pero **por el generador y Traefik, no por el motor** (los motores quedaron al 84 %). Con 6 núcleos de motor, 2 de Traefik y k6 en hilos hermanos en una sola máquina de 8 núcleos no se puede medir más; por escalado lineal se esperarían ~15.000/s, **sin medir**.
- Referencia: el pico de E1 (4.000 estudiantes en 15 minutos) son unas 4,4 solicitudes/s por consulta de estudiante a 1 consulta por estudiante; incluso con 20 consultas cada uno son ~90/s: una sola réplica sobra por 50x. El tamaño de réplicas lo decide la tolerancia a fallos, no la capacidad.
- **`SCHED_SEARCH_TIMEOUT_MS`: mantener 150.** No se activa con perfiles reales, así que no cuesta nada, y protege del caso patológico (en JMH, `backtracking` sin solución tarda 78 ms; entradas peores crecen exponencialmente). Bajarlo no está justificado con estos datos; subirlo rompería el margen sobre los 200 ms de E2.
- **`SCHED_MAX_COMBINATIONS`: mantener 5** (lo que se muestra al estudiante y lo medido aquí). Con el valor por defecto actual de 200 no se midió; devuelve y serializa 40 veces más datos y se espera un costo por solicitud claramente mayor. Conviene fijar 5 explícitamente en producción.
- No se cambió el código del motor: el costo por solicitud es repartido entre Tomcat, Spring y Jackson y no se perfiló ni apareció un cuello de botella claro y de bajo riesgo; el límite medido estaba en el generador y en Traefik.

## Limitaciones

- Una sola máquina de 8 núcleos físicos para motor, Traefik, NATS, k6 y la ráfaga; k6 comparte hilos hermanos con los motores en la prueba de 3 réplicas (pesimista para el motor).
- Una ejecución por punto, sin repeticiones; ruido visible cerca de la saturación. Valores a 6.000/s directo difieren mucho entre las dos corridas.
- Sin token ni TLS (a propósito): el costo de verificar Ed25519 no está incluido.
- La ráfaga vacía grupos populares (10/s), no un flujo mixto de decrementos de cupo.
- No se midió la distancia al top 5 exacto de las respuestas cortadas porque no hubo cortes.
