# Calentamiento de la JVM antes de `/ready`: efecto medido en el arranque en frío

Pregunta: ¿sirve que el motor ejecute consultas sintéticas en proceso antes de responder `200` en `/ready`? El problema, medido en
[`profile-server.md`](profile-server.md#por-qué-se-desborda-la-latencia-con-token): con una JVM recién arrancada y 2 núcleos, el compilador C2 consume ~0,6
núcleos durante ~30 s; si el pod recibe en ese momento ~3.500-4.000 solicitudes/s con token, las solicitudes se encolan y la latencia llega a segundos.
Medido el 2026-11-23 con `bench/warmup-compare.sh` (usa `bench/overflow-run.sh`, `bench/overflow-summary.py`, `bench/warmup-summary.py`).

## Resumen

1. **Funciona.** Con 2 núcleos, token y la rampa empezando apenas `/ready` responde `200`, el calentamiento deja los primeros 30 s con p50 de 0,5-1,2 ms y p99 de
   6-80 ms (según el tramo y la tasa) y **0 solicitudes encoladas**, contra p50 de 110-300 ms, p99 de 0,15 a 7 s (medias por tramo de 0,6 a 3,4 s) y 5.400-7.300 iteraciones que k6 no pudo lanzar a tiempo sin él
   (3 de 3 corridas de cada tasa; los rangos de p50 de los primeros 10 s no se solapan). El estado estable es el mismo (p50 de 0,44-0,46 ms, p99 de 4,6-4,7 ms a 3.500/s).
2. **Cuesta tiempo de arranque, no latencia:** `/ready` tarda 23-38 s en responder `200` (con 2 núcleos) en vez de 4,4-4,9 s; ese tiempo es el calentamiento (18-33 s) más el arranque de la JVM.
3. **No elimina el JIT de los primeros segundos:** tras el calentamiento el compilador aún usa ~0,9 núcleos en los primeros 10 s de tráfico (el camino HTTP real: Tomcat, Spring MVC y los filtros),
   pero sin encolar. A 4.000/s el tramo de 20-30 s llega a p99 de 46-80 ms (dentro del SLO de 200 ms de E2). `SCHED_WARMUP_HTTP=on` lo baja a 5-16 ms a cambio de ~26 s más de arranque (opción apagada por defecto).
4. **Sin errores:** 0 respuestas distintas de 200 y 422 en las 12 corridas de la comparación.

## Condiciones (iguales en todas las corridas)

- Equipo: AMD Ryzen 7 2700 (8 núcleos, 16 hilos), 30 GB, kernel 7.0, governor `schedutil`, Java 25 (Corretto), k6 v2.4.0, Docker 29.1.3. **Escritorio con otras aplicaciones abiertas** (ver "Amenazas").
- Motor: el jar (`server/target/sched-server-exec.jar`) en el host con `taskset -c 4,6` (2 núcleos), `-Xmx768m` y **`-XX:+UseSerialGC`** como el `Dockerfile`; `SCHED_ROLE=query`, `SCHED_AUTH=jwt` (EdDSA, tokens generados
  por `bench/profile-tokens.py`), `SCHED_MAX_COMBINATIONS=5`, `SCHED_SEARCH_TIMEOUT_MS=150`. NATS en contenedor y la instancia de ingesta en los núcleos 5 y 7, k6 en 8-15, la ráfaga de cupos en el 7. Catálogo del perfil `itm`
  (el mismo de [`profile-server.md`](profile-server.md)). El catálogo ya está cargado cuando arranca el motor.
- Carga: `bench/load-pico.js`, mezcla 80 % `/combinations` y 20 % `/alternatives`, rampa de 20 s hasta la tasa, meseta de 30 s de calentamiento de k6 + 60 s medidos, **tráfico que empieza ~2 s después de `/ready = 200`**
  (`READY_SLEEP=0 BOOT=2`; con calentamiento ese 200 llega ~25 s después del arranque; sin él, a los ~5 s). Los tramos son de 10 s desde el inicio de la rampa; "estado estable" es la meseta medida de k6.
- Corridas **alternadas** (off, on, off, on, off, on), 3 de cada una por tasa, 10 s de pausa entre corridas. Cada corrida arranca todo de cero. Valor = media (mínimo-máximo).
- La CPU del JIT es la de los hilos `C1/C2 CompilerThread` (ticks de `/proc`, 100 Hz, cada 0,5 s; se conserva el último valor de cada hilo porque la JVM retira los inactivos), en núcleos-segundo por tramo de 10 s.
  "Errores" = respuestas distintas de 200 y 422 en los tramos. "Encolamiento" = iteraciones que k6 no pudo lanzar a tiempo (`dropped_iterations`: aparecen cuando la latencia sube a segundos y k6 necesita más VUs) y VUs usados;
  el motor no tiene otra métrica de cola.

```sh
# 3.500/s (zona del desborde) y 4.000/s; 3 repeticiones alternadas cada una (~17 min por tasa). Requiere Docker, Java 25 y Python 3.
RATE=3500 REPS=3 WORK=/tmp/warmup-3500 bench/warmup-compare.sh
RATE=4000 REPS=3 WORK=/tmp/warmup-4000 bench/warmup-compare.sh
# Cada corrida es: AUTH=jwt RATE=$RATE READY_SLEEP=0 BOOT=2 SLICE_S=10 WARM=30 MEAS=60 ENGINE_OPTS=-XX:+UseSerialGC \
#                  ENGINE_ENV="SCHED_WARMUP=off|on" WORK=... SKIP_BUILD=1 bench/overflow-run.sh
python3 bench/warmup-summary.py --label off=/tmp/warmup-3500/off-1,... --label on=/tmp/warmup-3500/on-1,...   # resumen
```

## Resultados a 3.500/s

| Medida | Sin calentamiento (n=3) | Con calentamiento (n=3) |
|---|--:|--:|
| Segundos hasta `/ready` (desde el arranque del proceso) | 4,8 (4,7-4,9) | 28,9 (23,0-37,7) |
| p50 0-10 s (ms) | 296 (161-466) | 1,1 (1,0-1,1) |
| p99 0-10 s (ms) | 1.153 (728-1.557) | 18 (11-34) |
| p50 10-20 s (ms) | 275 (157-414) | 0,6 (0,5-0,7) |
| p99 10-20 s (ms) | 3.355 (1.367-6.980) | 12 (6-18) |
| p50 20-30 s (ms) | 113 (1,8-294) | 0,5 (0,5-0,5) |
| p99 20-30 s (ms) | 594 (147-1.424) | 17 (8-32) |
| Errores (no 200/422) en los primeros 30 s | 0 | 0 |
| Iteraciones descartadas por k6 (cola) | 5.358 (2.083-8.516) | 0 (0-0) |
| VUs usados (máx. de k6) | 1.131 (675-1.605) | 370 (370-370) |
| CPU del JIT 0-10 s (núcleos-s) | 7,8 (7,6-8,0) | 9,4 (9,2-9,6) |
| CPU del JIT 10-20 s | 6,4 (6,0-6,6) | 4,9 (4,4-5,9) |
| CPU del JIT 20-30 s | 5,2 (4,1-5,9) | 1,5 (1,3-1,7) |
| CPU del JIT desde el arranque hasta `/ready` | 4,9 (4,7-5,1) | 12,0 (11,7-12,2) |
| Estado estable p50 (ms) | 0,44 (0,43-0,45) | 0,46 (0,45-0,46) |
| Estado estable p99 (ms) | 4,7 (3,3-6,1) | 4,6 (3,6-6,2) |
| CPU del JIT en estado estable (núcleos) | 0,013 (0,011-0,018) | 0,012 (0,007-0,018) |
| CPU del motor en estado estable (% de 2 núcleos) | 59 (58-61) | 58 (57-59) |

| Corrida | `/ready` (s) | p99 0-10 | p99 10-20 | p99 20-30 | descartadas | p99 estable | carga media del equipo (`uptime`, 1 min; inicio → fin) |
|---|--:|--:|--:|--:|--:|--:|---|
| off 1 | 4,7 | 1.174 | 1.367 | 211 | 2.083 | 6,1 | 4,73 → 6,04 |
| off 2 | 4,7 | 728 | 6.980 | 147 | 5.475 | 4,6 | 4,42 → 6,25 |
| off 3 | 4,9 | 1.557 | 1.717 | 1.424 | 8.516 | 3,3 | 5,17 → 5,32 |
| on 1 | 25,9 | 11 | 18 | 12 | 0 | 6,2 | 4,55 → 5,16 |
| on 2 | 23,0 | 11 | 12 | 32 | 0 | 3,6 | 5,16 → 6,43 |
| on 3 | 37,7 | 34 | 6 | 8 | 0 | 3,9 | 4,43 → 4,95 |

## Resultados a 4.000/s

| Medida | Sin calentamiento (n=3) | Con calentamiento (n=3) |
|---|--:|--:|
| Segundos hasta `/ready` (desde el arranque del proceso) | 4,5 (4,4-4,6) | 25,3 (22,9-27,5) |
| p50 0-10 s (ms) | 264 (155-322) | 1,2 (1,2-1,3) |
| p99 0-10 s (ms) | 1.187 (596-2.357) | 25 (16-35) |
| p50 10-20 s (ms) | 274 (232-334) | 0,5 (0,5-0,6) |
| p99 10-20 s (ms) | 1.626 (1.347-2.026) | 13 (8-15) |
| p50 20-30 s (ms) | 217 (114-326) | 0,5 (0,5-0,5) |
| p99 20-30 s (ms) | 2.160 (360-5.075) | 62 (46-80) |
| Errores (no 200/422) en los primeros 30 s | 0 | 0 |
| Iteraciones descartadas por k6 (cola) | 7.310 (5.995-8.765) | 0 (0-0) |
| VUs usados (máx. de k6) | 1.345 (1.120-1.558) | 420 (420-420) |
| CPU del JIT 0-10 s (núcleos-s) | 7,4 (7,3-7,5) | 9,2 (9,0-9,5) |
| CPU del JIT 10-20 s | 6,8 (6,5-7,0) | 4,2 (4,2-4,2) |
| CPU del JIT 20-30 s | 4,7 (4,3-5,2) | 1,5 (1,2-2,0) |
| CPU del JIT desde el arranque hasta `/ready` | 4,6 (4,5-4,8) | 11,8 (11,4-12,0) |
| Estado estable p50 (ms) | 0,44 (0,44-0,45) | 0,45 (0,45-0,46) |
| Estado estable p99 (ms) | 9,3 (4,8-15,3) | 7,5 (6,8-8,1) |
| CPU del JIT en estado estable (núcleos) | 0,015 (0,011-0,020) | 0,013 (0,007-0,021) |
| CPU del motor en estado estable (% de 2 núcleos) | 65 (65-66) | 64 (64-65) |

| Corrida | `/ready` (s) | p99 0-10 | p99 10-20 | p99 20-30 | descartadas | p99 estable | carga media del equipo (`uptime`, 1 min; inicio → fin) |
|---|--:|--:|--:|--:|--:|--:|---|
| off 1 | 4,6 | 2.357 | 1.347 | 1.046 | 8.765 | 15,3 | 4,37 → 5,00 |
| off 2 | 4,4 | 609 | 2.026 | 360 | 5.995 | 4,8 | 4,79 → 5,55 |
| off 3 | 4,5 | 596 | 1.504 | 5.075 | 7.171 | 7,8 | 3,55 → 5,46 |
| on 1 | 22,9 | 24 | 15 | 59 | 0 | 6,8 | 4,51 → 5,32 |
| on 2 | 27,5 | 35 | 15 | 80 | 0 | 8,1 | 4,37 → 4,37 |
| on 3 | 25,4 | 16 | 8 | 46 | 0 | 7,7 | 4,61 → 5,21 |

Lectura: el calentamiento no quita CPU de JIT, la **mueve antes de `/ready`** (4,6-4,9 → 11,8-12,0 núcleos-s hasta `/ready`) y quita la competencia con las solicitudes: en la fase fría sin calentamiento la réplica
tiene 2 núcleos para ~1.800-2.000 solicitudes/s de la rampa y el JIT, y no alcanza. Aun con calentamiento, el JIT de los primeros 10 s de tráfico es de ~9 núcleos-s porque faltan por compilar el camino HTTP real (ver abajo):
cabe porque la rampa aún es baja (350-1.900/s en ese tramo); a 4.000/s el tramo de 20-30 s (carga completa + ~1,5 núcleos-s de JIT) sube a p99 de 46-80 ms.

## Criterio de parada: datos

El calentamiento termina cuando **3 ventanas de 1 s seguidas compilan menos de 30 ms** de tiempo de compilación JIT (`CompilationMXBean.getTotalCompilationTime`, suma entre compiladores) y han pasado al menos
`SCHED_WARMUP_MIN_S` = 10 s; o a los `SCHED_WARMUP_MAX_S` = 60 s. Elegido con la serie de una corrida de reconocimiento (2 hilos, `LOGGING_LEVEL_SCHED_SERVER=debug`, con token), compilación por ventana en ms:

```
t(s)  2    3    4    5    6    7    8    9    10   11   12   13   14   15   16   17
ms   1694 1210 1004 1036  839 1201  878  934  278  247   40   17   52   10   29    4
```

- La compilación se mantiene en ~1 s por ventana (1 núcleo entre los dos compiladores) durante ~9 s y cae a <300 ms entre los s 10 y 11 y a <60 ms desde el s 12. Un umbral de 30 ms deja pasar el ruido de fondo (el JIT no llega a 0:
  10-52 ms por ventana tras calentar) y obliga a que sea de verdad el final de la compilación.
- **Tres** ventanas y no una: la ventana del s 13 (17 ms) iba seguida de una de 52 ms; con una sola ventana se habría parado en el s 13 con el JIT aún trabajando. Con tres se paró a los 17 s.
- Mínimo de 10 s: en la corrida de reconocimiento el JIT sigue por encima de 200 ms/ventana hasta pasados los 10 s (y las duraciones medidas, de 18 s o más, nunca lo necesitaron); el mínimo evita parar por una ventana tranquila temprana (p. ej. mientras se cargan clases) y no cambia el resultado normal.
- Máximo de 60 s: tope duro para que el pod nunca quede no-listo por un JIT que no se calma (CPU escasa, otro proceso ocupando los núcleos). Con 2 núcleos libres no se alcanzó en ninguna corrida.
- Duración medida con 2 núcleos, 6 corridas con calentamiento (3 a 3.500/s y 3 a 4.000/s): 18-33 s (mediana ~21 s), 143.000-339.000 búsquedas sintéticas, motivo siempre "JIT estable", ~9,3-10,6 s de tiempo de compilación.
- Variantes no elegidas: por número de iteraciones o tiempo fijo (no se adapta a la CPU disponible: en un pod con 1 núcleo tardaría más y con 8 pararía tarde); por estabilidad de la latencia de las propias búsquedas (ruidosa y no mide el JIT pendiente).

## `SCHED_WARMUP_HTTP`: calentar también Tomcat (opcional, apagado por defecto)

Tras calentar solo el solver, el JSON y la firma, el perfil de compilación de las siguientes 7.000 compilaciones (`-XX:+PrintCompilation`, 3.500/s con token) muestra que lo que falta es el camino HTTP:
`org.springframework.web` 750, `org.apache.catalina` 698, `org.apache.tomcat` 582, `java.util.concurrent` 454, `org.apache.coyote` 426, `org.springframework.http` 311, `sun.nio.ch` 242, `tools.jackson.databind` 213, `sched.adapters.http` 106.
La opción pide `GET /health` a `localhost` desde los mismos hilos (ruta pública de la sonda: sin token, sin atajo, y `/health` no entra en `sched_http_*`). Resultado, 3 corridas alternadas de cada una a 3.500/s:

| Medida | `SCHED_WARMUP_HTTP=off` (n=3) | `SCHED_WARMUP_HTTP=on` (n=3) |
|---|--:|--:|
| Segundos hasta `/ready` | 25,3 (23,6-26,4) | 51,1 (41,6-56,9) |
| p50 0-10 s (ms) | 1,1 (1,0-1,4) | 0,7 (0,7-0,7) |
| p99 0-10 s (ms) | 38 (18-78) | 5 (5-6) |
| p99 10-20 s (ms) | 17 (5-28) | 4 (4-4) |
| p99 20-30 s (ms) | 24 (20-31) | 16 (7-20) |
| Descartadas por k6 | 16 (0-47) | 0 (0-0) |
| p99 en estado estable (ms) | 5,1 (3,5-7,5) | 5,2 (4,3-6,4) |

Ayuda a la latencia de los primeros 30 s (p99 de ~25-40 ms a ~5-15 ms), pero **duplica el tiempo hasta `/ready`** (el JIT compila el camino HTTP durante el calentamiento: ~31 s de tiempo de compilación contra ~10 s) y se acerca al máximo de 60 s. Como sin ella el p99 ya
queda dentro del SLO de 200 ms (E2) y el tiempo de arranque cuenta al escalar, se deja **apagada**; actívela si el SLO es más estricto (y suba `SCHED_WARMUP_MAX_S`). (Las corridas de esta tabla usaron el muestreador antiguo de CPU del JIT: no se reporta esa fila.
En un primer lote, 1 de 3 corridas con HTTP se descartó: con una carga ajena de 13 de carga media en el equipo y 0,19 núcleos ajenos en los núcleos del motor tuvo p99 de cientos de ms durante la meseta; y otra abortó por una condición de carrera del script al tomar una foto, ya corregida.)

## Amenazas a la validez y lo que no se verificó

- **Escritorio con otras aplicaciones abiertas.** La carga media de 1 minuto (`uptime`) estaba entre 3,5 y 6,5 durante todas las corridas (columna de cada tabla), con 16 hilos lógicos. El motor y NATS van fijados a sus núcleos (4, 5, 6, 7) y k6 a 8-15, y la CPU ajena
  medida en los núcleos del motor fue ≤ 0,03 núcleos en las 12 corridas de la comparación, pero la caché L3, el ancho de banda de memoria y el reloj (governor `schedutil`, sin turbo fijo) no están aislados. Un lote previo se vio afectado así (arriba).
- n = 3 por configuración y tasa. Con los rangos observados las diferencias de p50 y de cola de los primeros 30 s (de 2 a 3 órdenes de magnitud) no se solapan; la de "CPU del JIT 0-10 s" (7,8 contra 9,4) sí está cerca del ruido y no se interpreta.
- `taskset` con 2 hilos lógicos no es un límite de CPU de cgroup (en Kubernetes con `limits.cpu` hay estrangulamiento por cuota, que no se midió), ni hay Traefik ni red real. El motor corre como jar en el host.
- El tráfico empieza ~2 s después de `/ready`; en producción el balanceador tarda lo que dure su ciclo de sondeo (en Kubernetes, `periodSeconds` de la `readinessProbe`).
- No se midió con otro hardware, con 1 o 4 núcleos, ni el tiempo de calentamiento dentro de un contenedor con cuota de CPU. En un equipo más lento el calentamiento puede llegar al máximo (60 s): el log lo dice (`motivo=tiempo máximo`).
- La recomendación de profile-server.md ("ganancia esperada: la fase fría desaparece") se cumple en lo que importa (sin encolamiento, p50 sub-ms), no del todo: queda un p99 de 5-80 ms en los primeros 30 s, por el JIT del camino HTTP real.
- No se midió el costo en memoria del calentamiento (el heap tras calentar con Serial); las métricas de memoria no se compararon.

## Archivos

- `bench/warmup-compare.sh` (comparación alternada), `bench/warmup-summary.py` (resumen por tramos), cambios en `bench/overflow-run.sh` (`READY_SLEEP`, `ready_ms.txt`, `times.txt`, `loadavg.txt`, `jit_series.txt`; la espera del puerto de administración y las
  fotos de CPU toleran un hilo que termina) y `bench/load-pico.js` (errores y solicitudes por tramo).
- Datos crudos (k6 `.json`, series del JIT, logs) en el directorio temporal de la sesión; no se versionan.
