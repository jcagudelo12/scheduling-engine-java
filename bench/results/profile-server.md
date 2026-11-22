# Perfil del motor bajo la carga del pico de matrícula

Objetivo: saber en qué se va la CPU por solicitud que `report-pico.md` estimó en ~0,25 ms ("la búsqueda es una parte
pequeña, el resto es HTTP/JSON/filtros (no se perfiló)"). Solo se midió: no se tocó código de `src/main`.

**Resumen corto**

- **Sin token** (la configuración con la que corrió el pico, `SCHED_AUTH=none`): a 4.000 solicitudes/s el motor gasta
  **221 us de CPU por solicitud** (236 us en una ventana sin profiler), coherente con los ~0,25 ms de `report-pico.md`.
  El 41 % es Tomcat y E/S de red (sobre todo llamadas al sistema: `write`, `read`, `epoll`), el 11 % Spring MVC,
  el 8 % serializar JSON, el 7,5 % el solver. La búsqueda (`ForwardCheckingSolver`) son ~17 us, de acuerdo con
  los ~25 us de JMH. Los filtros propios, la validación y el seguidor de NATS no llegan al 3 % juntos.
- **Con JWT EdDSA (producción)**: **la verificación de la firma Ed25519 consume el 70,6 % de la CPU: ~790 us por solicitud**,
  3,5 veces el costo de todo lo demás junto. Una sola verificación del `Signature` Ed25519 del JDK 25 tarda ~870 us en esta
  máquina (medido aparte, sin el motor). Con 2 núcleos, una réplica con JWT satura a ~1.700 solicitudes/s, no a ~5.000.
  **La capacidad de `report-pico.md` (5.000/s por réplica, "sin token a propósito") no vale para producción.**

## 1. Entorno y cómo reproducirlo

| | |
|---|---|
| Máquina | AMD Ryzen 7 2700 (8 núcleos, 16 hilos), 30 GB, kernel 7.0.0-44-generic, governor `schedutil` (el mismo de `pico_environment.md`) |
| JVM del motor | Corretto 25.0.4.1 (`java` del host), `-Xmx768m` (lo que da `MaxRAMPercentage=75` con 1 GB, como la imagen), G1, `-XX:+UnlockDiagnosticVMOptions -XX:+DebugNonSafepoints` |
| Motor | `server/target/sched-server-exec.jar` del `HEAD` de la rama, **en el host** (no en contenedor), 1 réplica `SCHED_ROLE=query`, núcleos `4,6` (`taskset`, como `ENGINE1_CPUS`), `SCHED_MAX_COMBINATIONS=5`, `SCHED_SEARCH_TIMEOUT_MS=150`, hilos virtuales activos |
| Otros procesos | NATS (contenedor `nats:2`, núcleos 5,7), ingesta (jar, 5,7), ráfaga de cupos `seat-storm` (núcleo 7, 6 grupos/s), k6 en contenedor con `--network host` y núcleos 8-15. **Directo a la réplica, sin Traefik** |
| Profiler | async-profiler **4.5** (`asprof` y `jfrconv` que trae Corretto en `/usr/lib/jvm/java-25-amazon-corretto/bin`; no hizo falta descargar nada) |
| Modo de CPU | `perf_event_paranoid=4` (perf_events no está disponible sin root): se usó `-e ctimer -i 1ms` (temporizador por hilo, mide CPU de usuario y de núcleo). Asignación: `-e alloc`. Espera: `-e wall -i 5ms`. Salida JFR convertida con `jfrconv` a `collapsed` (con el nombre del hilo en la raíz) y HTML |
| Datos | `generate_scenario.py --profile itm` (semilla fija), mezcla de `load-pico.js`: ~80 % `/combinations`, ~20 % `/alternatives`, más la sonda de E1 (100/s) y la ráfaga de cupos |

Tres corridas (una por punto, sin repeticiones). Cada una: rampa de 20 s, meseta de 235 s con calentamiento de 30 s y luego
las ventanas `base` (30 s, sin profiler), `cpu` (60 s), `alloc` (60 s) y `wall` (30 s), separadas por 5 s. En cada ventana se
miden las solicitudes (suma de `sched_http_latency_seconds_count` de `/actuator/prometheus`) y la CPU del proceso
(`utime+stime` de `/proc/<pid>/stat`), de donde sale los us de CPU por solicitud.

| Corrida | Auth | Tasa en la meseta | Resultado de k6 en la meseta (incluye las ventanas con profiler) |
|---|---|---|---|
| **A** | `SCHED_AUTH=none` | 4.000/s (+100/s de sonda) | p50 0,32 ms, p95 0,83 ms, p99 4,5 ms, 0 % de errores, 939.837 solicitudes |
| **B** | `SCHED_AUTH=none` | 1.200/s (+100/s) | p50 0,57 ms, p95 0,86 ms, p99 2,6 ms, 0 % de errores |
| **C** | `SCHED_AUTH=jwt` (EdDSA) | 1.200/s (+100/s) | p50 1,3 ms, p95 60 ms, p99 141 ms (máx. 5,1 s), 0 % de errores |

C no se corrió a 4.000/s porque con JWT el motor se satura: un intento a 4.000/s (abortado, solo se tiene la ventana
`base`) logró 1.690 solicitudes/s con los 2 núcleos al 99 % (1.170 us por solicitud). Por eso A es la comparación con el pico
original y B y C comparten tasa. La latencia de C no es representativa (el motor está al ~75 % de sus 2 núcleos y hay colas; el
máximo de 5 s probablemente es el arranque en frío de la firma, **no verificado**).

CPU por solicitud en cada ventana (us): la CPU **no es constante con la tasa** (a 1.200/s el costo por solicitud es el doble
porque no se amortizan los despertares de hilos ni las llamadas al sistema; ver §4).

| Corrida | `base` (sin profiler) | `cpu` (con profiler) | `alloc` | `wall` |
|---|---:|---:|---:|---:|
| A: sin JWT, 4.000/s | 236 (123.709 sol., 29,2 s CPU) | 221 (247.146 sol., 54,5 s) | 218 | 205 |
| B: sin JWT, 1.200/s | 526 | 490 (78.260 sol., 38,3 s) | 461 | 467 |
| C: JWT, 1.200/s | 1.128 | 1.114 (78.452 sol., 87,4 s) | 1.089 | 1.098 |

El profiler casi no cambia la CPU (±7 %, dentro del ruido entre ventanas).

**Pasos para reproducir** (desde la raíz; requiere Docker, Java 25 con async-profiler 4.x, Python 3 y, para JWT, `cryptography`):

```sh
# 1. Sin token, como el pico. WORK queda fuera del repo (ahí quedan los .jfr, .collapsed y .html).
WORK=/tmp/perfil-sinjwt AUTH=none RATE=4000 bench/profile-server.sh
# 2. Con JWT (genera el par de llaves y los tokens en $WORK/jwt, fuera del repo; vencen a los 900 s)
WORK=/tmp/perfil-jwt AUTH=jwt RATE=1200 bench/profile-server.sh
# 3. Desglose, con las solicitudes y los ms de CPU de la ventana "cpu" que imprime el script ($WORK/<auth>_ventanas.txt)
python3 bench/profile-breakdown.py cpu   /tmp/perfil-sinjwt/none_cpu.collapsed   247146 54510 --top 15
python3 bench/profile-breakdown.py alloc /tmp/perfil-sinjwt/none_alloc.collapsed 245959 --top 10
```

`bench/profile-server.sh` hace todo: genera los datos, compila los jars, levanta NATS e ingesta, carga el catálogo, arranca
el motor con `taskset`, lanza k6 y la ráfaga de cupos alineados, toma las ventanas y convierte los perfiles; al terminar (o
si falla) detiene los procesos y el contenedor de NATS y k6. Variables: `AUTH`, `RATE`, `WARM`, `CPU_S`, `ALLOC_S`, `WALL_S`,
`ENGINE_CPUS`, `ENGINE_HEAP`, `ASPROF_DIR`, `WORK`. `bench/profile-tokens.py` firma los tokens (uno por cuerpo de
`students.json` y `alternatives.json`, con `sub` y `courses` iguales al cuerpo); `bench/load-pico.js` ahora acepta
`TOKENS_COMBOS_FILE` y `TOKENS_ALTS_FILE` (sin ellos se comporta igual que antes).

## 2. Desglose de la CPU por solicitud

Cada muestra del perfil `cpu` se asigna a **una** categoría (las columnas suman 100 %): primero por hilo (GC, JIT, seguidor de
NATS, poller de Tomcat) y, en los hilos de solicitud (portadores de los hilos virtuales), caminando desde la hoja de la pila
hacia la raíz hasta el primer marco con regla (el componente más profundo posee el tiempo); lo que es JDK sin regla (`java.util`,
`String`...) se le atribuye a quien lo llamó. Las reglas están en `bench/profile-breakdown.py` (función `classify_frame`).
us = fracción de muestras x CPU por solicitud de la ventana. Los perfiles tienen ~39.000 (A), ~29.000 (B) y ~71.000 (C) muestras.

| Categoría | A % | A us | B % | B us | C % | C us |
|---|--:|--:|--:|--:|--:|--:|
| **JWT: firma EdDSA (Ed25519)** | 0,0 | 0,0 | 0,0 | 0,0 | **70,6** | **786,5** |
| JWT: decodificación y claims (base64 + JSON) | 0,0 | 0,0 | 0,0 | 0,0 | 0,9 | 9,6 |
| Parseo JSON de entrada (cuerpo) | 3,6 | 7,9 | 2,7 | 13,3 | 1,0 | 11,5 |
| Serialización JSON de salida | 8,2 | 18,2 | 8,0 | 39,0 | 2,4 | 27,2 |
| Validación (`RequestValidation`) | 0,3 | 0,8 | 0,3 | 1,4 | 0,1 | 1,4 |
| Solver (`ForwardCheckingSolver`, incluye el costo `Preferences.penaltyOf`) | 7,5 | 16,6 | 10,3 | 50,2 | 3,1 | 34,1 |
| Catálogo / réplica (`CatalogReplica`, `Catalog.openSectionsOf`...) | 2,1 | 4,6 | 1,5 | 7,6 | 0,9 | 9,9 |
| Casos de uso y dominio (`GenerateCombinations`, `SuggestAlternatives`) | 2,7 | 5,9 | 3,4 | 16,8 | 1,1 | 12,4 |
| Controladores: mapeo DTO <-> dominio (`toDto`, `parseAvoid`) | 3,8 | 8,3 | 5,9 | 28,7 | 2,1 | 23,1 |
| Filtro `RequestIdFilter` (casi todo `UUID.randomUUID`) | 0,8 | 1,8 | 1,0 | 4,7 | 0,1 | 1,3 |
| Filtro `BodySizeLimitFilter` | 0,0 | 0,1 | 0,1 | 0,4 | 0,0 | 0,3 |
| Filtro `JwtAuthFilter` (sin la verificación) | 0,0 | 0,0 | 0,0 | 0,0 | 0,1 | 1,2 |
| Filtro `HttpMetricsFilter` (propio, sin el timer) | 0,3 | 0,8 | 0,3 | 1,7 | 0,1 | 0,8 |
| Filtro `AppSecurityHeadersFilter` | 0,0 | 0,1 | 0,0 | 0,1 | 0,0 | 0,0 |
| Filtros estándar de Spring (observación, encoding, form, contexto) | 1,2 | 2,7 | 1,0 | 5,0 | 0,3 | 3,3 |
| Métricas Micrometer (timer propio + observación `http.server.requests`) | 4,7 | 10,4 | 4,7 | 23,2 | 1,1 | 12,8 |
| Logging (logback, MDC) | 0,7 | 1,5 | 0,6 | 2,9 | 0,1 | 1,3 |
| Spring MVC (despacho, argumentos, reflexión, negociación de contenido) | 11,0 | 24,3 | 10,5 | 51,3 | 4,1 | 46,2 |
| Tomcat / E/S de red (parseo HTTP, `write`/`read`, `epoll`, poller) | 41,4 | 91,3 | 36,5 | 178,5 | 9,5 | 106,2 |
| Hilos virtuales (planificador ForkJoin, park/unpark) | 5,4 | 12,0 | 5,4 | 26,3 | 0,6 | 6,8 |
| GC (G1: concurrente y pausas) | 4,4 | 9,7 | 5,8 | 28,4 | 0,3 | 3,2 |
| JIT (C1/C2) | 1,2 | 2,6 | 1,4 | 6,6 | 1,1 | 12,6 |
| Seguidor de NATS (`CatalogFollower`) | 0,1 | 0,2 | 0,2 | 1,0 | 0,0 | 0,5 |
| Otros hilos de JVM / Tomcat de fondo | 0,1 | 0,3 | 0,2 | 0,9 | 0,0 | 0,2 |
| Otros (sin regla) | 0,3 | 0,6 | 0,4 | 1,8 | 0,2 | 1,7 |
| **Total** | 100 | **221** | 100 | **490** | 100 | **1.114** |

A = sin JWT a 4.000/s (la comparable con el pico); B = sin JWT a 1.200/s; C = JWT a 1.200/s.

Detalle de lo grande (de los perfiles, % de la CPU total de A):

- **Tomcat / E/S, 41 %**: la mayor parte son llamadas al sistema, no código Java de Tomcat. `write()` del socket (respuesta) 11,2 %, hilo
  poller de Tomcat (`epoll_wait`/selector) 10,4 %, `read()` del socket 3,6 %, `epoll_ctl` (re-registrar el socket tras cada
  solicitud) 2,3 %, despertares de hilos (futex, `pthread_cond_signal`) 3,9 %. El resto es el parseo HTTP (`parseRequestLine` 4,4 %
  incluye su `read`). **Ojo, artefacto de la prueba**: k6 corre en la misma máquina por loopback, y en Linux el `write` sobre un
  socket local ejecuta también el procesamiento TCP de recepción del otro extremo en el hilo que escribe, así que parte de ese
  11 % es el costo del lado del cliente atribuido al motor. En producción (red real, Traefik delante) será distinto; no se midió.
- **Serialización JSON 8,2 %** (18 us por respuesta de 5 combinaciones): `UTF8JsonGenerator`, `BeanPropertyWriter`, acceso a los
  récords por `MethodHandle`. **Parseo JSON de entrada 3,6 %** (8 us). Jackson 3.
- **Spring MVC 11 %** (24 us): despacho, resolución de argumentos, `HashMap`/`ConcurrentHashMap` de reflexión, negociación de
  contenido (`writeWithMessageConverters`). Sin un hotspot único (marcos hoja de 0,2-0,4 % cada uno).
- **Solver 7,5 %** (16,6 us por solicitud en promedio: `Search.<init>` 5,5 % inclusive, `SlotBitmaps.of` 2,1 %,
  `Arrays.binarySearch`, `compareTo` de `String`); coherente con los ~25 us de JMH para 5 resultados con costo, porque parte de la
  mezcla (`/alternatives`) hace menos búsqueda.
- **Micrometer 4,7 %** (10 us): `SimpleObservation.start/stop` de la observación `http.server.requests` de Spring (~3,8 %, tags y
  `KeyValues`), más el `LatencyTimer` propio (`Timer.record`, `MeterRegistry.timer`).
- **GC 4,4 %**: pausas de GC young 634 en toda la corrida A, 1,14 s en total (~0,4 % del tiempo): el costo del GC es sobre todo
  trabajo concurrente de G1 (marcado), no pausas.

## 3. Top de métodos propios y de asignación

### Top 15 de métodos propios (`sched/*`), inclusive, corrida A (% de las muestras de CPU)

Los tres primeros son la cadena de filtros que envuelve toda la solicitud (inclusive no dice cuánto cuestan ellos: su costo propio
está en la tabla de §2, filas de filtros). "Propio" es el % en que el método es la hoja de la pila.

| Método | Inclusive | Propio (hoja) |
|---|---:|---:|
| `sched.adapters.http.RequestIdFilter.doFilterInternal` | 51,5 % | 0,1 % |
| `sched.server.HttpMetricsFilter.doFilterInternal` | 44,7 % | 0,1 % |
| `sched.adapters.http.BodySizeLimitFilter.doFilterInternal` | 42,4 % | 0,0 % |
| `sched.adapters.http.CombinationsController.combinations` | 11,5 % | 0,0 % |
| `sched.application.GenerateCombinations.execute` | 8,3 % | 0,0 % |
| `sched.solver.ForwardCheckingSolver.search` | 6,8 % | 0,4 % |
| `sched.solver.ForwardCheckingSolver$Search.<init>` | 5,5 % | 0,5 % |
| `sched.adapters.http.AlternativesController.alternatives` | 4,8 % | 0,0 % |
| `sched.application.SuggestAlternatives.execute` | 3,5 % | 0,1 % |
| `sched.application.CatalogReplica.read` | 2,6 % | 0,0 % |
| `sched.adapters.http.CombinationsController$$Lambda.apply` (mapeo de secciones) | 2,2 % | 0,0 % |
| `sched.adapters.http.CombinationsController.toDto` | 2,1 % | 0,0 % |
| `sched.solver.SlotBitmaps.of` | 2,1 % | 0,5 % |
| `sched.application.GenerateCombinations$$Lambda.read` (lectura del catálogo) | 1,9 % | 0,0 % |
| `sched.application.GenerateCombinations.lambda$execute$0` | 1,9 % | 0,0 % |

Corrida C (JWT), cabeza de la lista: `JwtAuthFilter.doFilterInternal` 88,1 %, `JwtVerifier.verify` 71,5 %,
`JwtVerifier.signatureMatches` 70,6 % (de ahí `java.security.Signature.verify` 66,9 % y `Signature.initVerify` 3,6 %: decodificar
la llave pública del `kid` en cada solicitud); dentro de la firma, `Ed25519Operations.setSum` 34 %, `basePointMultiply` 27 %,
`setDouble` 26 % y `decodeAffinePoint` 6 %; la hoja caliente es `IntegerPolynomial25519.mult` (28 % del total de CPU) y `carryReduce`
(15 %): aritmética de campo en Java puro, sin instrucciones nativas.

### Top 10 de sitios de asignación, corrida A (45.493 B por solicitud en el perfil; el contador de JVM da ~47,8 KB)

`jvm_gc_memory_allocated_bytes_total` / solicitudes de toda la corrida = ~47,8 KB por solicitud, de acuerdo con los 45,5 KB del perfil.
Tabla: método que asigna y clase asignada.

| Sitio | Clase | B/solicitud | % |
|---|---|---:|---:|
| `java.util.stream.StreamSupport.stream` | `ReferencePipeline$Head` | 3.700 | 8,1 % |
| `java.util.stream.ReferencePipeline.map` | `ReferencePipeline$3` | 1.646 | 3,6 % |
| `java.util.List.spliterator` | `AbstractList$RandomAccessSpliterator` | 1.620 | 3,6 % |
| `java.util.HashMap.resize` | `HashMap$Node[]` | 1.292 | 2,8 % |
| `java.lang.Integer.valueOf` | `Integer` | 1.219 | 2,7 % |
| `sched.solver.ForwardCheckingSolver$Search.<init>` | `long[]` | 1.134 | 2,5 % |
| `java.util.concurrent.ConcurrentHashMap.putVal` | `ConcurrentHashMap$Node` | 1.079 | 2,4 % |
| `java.lang.StringLatin1.toLowerCase` | `String` | 1.055 | 2,3 % |
| `java.lang.StringLatin1.toLowerCase` | `byte[]` | 1.008 | 2,2 % |
| `jdk.internal.misc.Unsafe.allocateUninitializedArray` | `byte[]` | 874 | 1,9 % |

Los sitios son de JDK; quién los provoca (el método propio más cercano a la hoja, sin contar los filtros que envuelven todo):

| Método propio | B/solicitud | % |
|---|---:|---:|
| (sin código propio en la pila: Tomcat, Spring, Jackson, filtros) | 20.689 | 45,5 % |
| `CombinationsController.lambda$toDto$0` (secciones -> DTO, `streams`) | 4.221 | 9,3 % |
| `Preferences.lambda$penaltyOf$0` | 3.076 | 6,8 % |
| `Preferences.penaltyOf` | 2.812 | 6,2 % |
| `CombinationsController.lambda$toDto$1` (franjas -> DTO, `name().toLowerCase()`) | 1.961 | 4,3 % |
| `ForwardCheckingSolver$Search.<init>` | 1.886 | 4,1 % |
| `AlternativesController.lambda$toDto$1` | 1.023 | 2,2 % |
| `CombinationsController.toDto` | 944 | 2,1 % |
| `SlotBitmaps.of` | 750 | 1,6 % |
| `Problem.<init>` | 708 | 1,6 % |

Por categoría (B/solicitud, A): controladores/DTO 10.381 (22,8 %), solver 8.251 (18,1 %), Spring MVC 8.245 (18,1 %), casos de uso y
dominio 4.694, Tomcat 3.492, Micrometer 2.829, JSON entrada 2.714, JSON salida 1.874. Con JWT (C): 104.314 B por solicitud, de ellos
**51.439 B (49 %) son de la firma Ed25519** (`BigInteger`, `IntegerPolynomial$Element`: objetos de aritmética de campo) y 5.355 B
de la decodificación del token. El GC con JWT casi no se ve (0,3 % de la CPU) porque la firma domina el total.

### Espera (`wall`, 30 s, corrida A)

Los portadores de los hilos virtuales pasan el 76 % del tiempo parados (`park`/`awaitWork`: el motor está al ~0,9 de sus 2
núcleos), 5 % en `write`, **<1 % en candados** (`ReentrantLock`, `synchronized`, `Object.wait`). No hay contención. El resto de
hilos (NATS, Catalina) están ociosos.

## 4. Conclusiones

**Qué domina.**

1. **Con JWT, la firma EdDSA y nada más**: 786 us de 1.114 us (70,6 % de la CPU y 49 % de la memoria asignada), y la decodificación
   del token y los claims otros 10 us. Es la implementación de Ed25519 del JDK 25 en Java puro (`IntegerPolynomial25519.mult`): ~870 us por
   verificación medidos aparte con `java EdBench` (5.000 verificaciones seguidas, 1 núcleo del Ryzen 2700 a ~3,4 GHz). Una máquina más
   moderna será más rápida (no medido). Este es el hallazgo central y **no estaba en `report-pico.md`**: con token, una réplica de 2
   núcleos sostiene ~1.700 solicitudes/s (medido, saturada) y no ~5.000.
2. **Sin JWT, la capa HTTP del servidor**: Tomcat y E/S 41 % (casi todo llamadas al sistema) y Spring MVC 11 %: el 52 % es "mover bytes"
   y despachar. JSON 12 % (8 % salida, 4 % entrada). El **solver es el 7,5 %** (17 us), y con él el catálogo, los casos de uso y el mapeo a DTO,
   la lógica propia de la solicitud es ~16 %.
3. **No son relevantes**: la cadena de filtros propios (`RequestIdFilter` 0,8 %, `BodySizeLimitFilter` 0,0 %, `HttpMetricsFilter` propio
   0,3 %, `AppSecurityHeadersFilter` 0,0 %, `JwtAuthFilter` sin verificar 0,0-0,1 %: ~1,1 % entre todos; con los filtros estándar de Spring,
   ~2,3 %), la validación (0,3 %), el logging (0,7 %), el seguidor de NATS (0,1 %), el JIT (1,2 %) y los candados (<1 % del wall).
   **El JSON es moderadamente relevante (12 %), los filtros no, y la verificación EdDSA es, con diferencia, lo más relevante cuando
   el token está activo.**
4. **El costo por solicitud depende de la tasa**: A (4.000/s) 221 us, B (1.200/s) 490 us. A tasa baja cada solicitud paga su propio
   despertar de hilos y sus llamadas al sistema sin amortizarlos (Tomcat/E/S pasa de 91 a 179 us, GC y planificador también). Con JWT a
   1.200/s el resto (todo menos la firma) es ~327 us, menos que B porque los portadores están ocupados con la firma y casi no se duermen.
   Por eso el "~0,25 ms" de `report-pico.md` es válido para la carga alta sin token y no se puede extrapolar a otras tasas.

**Optimizaciones candidatas y ganancia máxima teórica** (techo = la fracción de CPU de la categoría, si su costo se redujera a cero;
no se implementó nada ni se midió ninguna alternativa):

| Candidata | Fracción de CPU (techo) | Comentario |
|---|---|---|
| Cachear la verificación del token (clave: el token completo, entrada hasta su `exp`, tamaño acotado) | **hasta ~70 % con JWT** (787 us de 1.114); capacidad de ~1.700 a, como máximo, ~5.000/s | Solo ahorra en los aciertos: con *h* de aciertos la ganancia es 0,7·*h*. En esta carga cada token se usó unas 8 veces; el *h* real depende de cuántas consultas haga cada estudiante con un mismo token (vida de 600-900 s). No cambia la seguridad si la entrada nunca se usa pasado `exp`, pero hay que decidirlo (ADR 0004). Además evita decodificar la llave pública por solicitud (3,6 %) |
| Otra implementación de Ed25519 (librería con aritmética nativa/optimizada) | hasta ~70 % con JWT; la ganancia real **no se midió** | Mide primero: no hay dato propio de cuánto bajaría; dependencia nueva |
| Menos llamadas al sistema por solicitud (menos `epoll_ctl`, agrupar escrituras, mantener conexiones) | hasta ~21 % (write 11 %, read 4 %, epoll_ctl 2 %, futex 4 %) en teoría, pero casi no controlable desde el código | Parte del `write` es artefacto de loopback (ver §2); verificar en la red real con Traefik antes de actuar |
| Serialización JSON (menos campos, respuestas más cortas, `toLowerCase` por franja) | hasta ~8 % salida + ~4 % entrada | Con `SCHED_MAX_COMBINATIONS=5` ya es poco; el techo es optimista |
| Observación de Spring `http.server.requests` duplicada con el `LatencyTimer` propio | hasta ~4 % (Micrometer 4,7 %: la observación `SimpleObservation` ~3,8 %) | Si se pueden desactivar las métricas de Spring que no se usan, la ganancia es de ese orden |
| Asignaciones: `streams` y `toLowerCase` en `toDto`, `Preferences.penaltyOf`, `Search.<init>` | GC 4,4 % + parte del solver y DTO (3-5 % más) ⇒ hasta ~5-9 % | 45 KB por solicitud; el GC casi no tiene pausas (0,4 % del tiempo), por lo que afecta a la CPU y no a la latencia |
| Solver (`ForwardCheckingSolver`) | hasta 7,5 % | Ya optimizado (comparación por rangos enteros); no es una prioridad |
| `RequestIdFilter` (`UUID.randomUUID` -> `SecureRandom`) | hasta 0,8 % | No vale la pena |
| Filtros, validación, logging, NATS | <= 1 % cada uno | No tocar |

Sin JWT ninguna candidata supera el ~8 % por sí sola: el costo está repartido y dominado por Tomcat/E/S, que depende en gran parte de la
prueba (loopback). Con JWT, una sola (la verificación) es el 70 %.

**Impacto para el dimensionamiento.** Con JWT una réplica de 2 núcleos admite ~1.700 solicitudes/s (medido, saturada); a 1.200/s ya
usa ~1,5 núcleos (74 %), con p99 de decenas de ms. El pico de E1 (~90 solicitudes/s con 20 consultas por estudiante, según
`report-pico.md`) cabe holgado en una réplica incluso con JWT (90 x 1,1 ms = 0,1 núcleo), por lo que el hallazgo importa para
tasas mucho mayores que las del anteproyecto, o para el escenario de ráfaga de tokens nuevos (que no se probó).

## 5. Limitaciones y lo que no se verificó

- Una sola ejecución por punto, sin repeticiones; el ruido entre ventanas es de unos ±7 % (ver la tabla de CPU por ventana).
- `ctimer` a 1 ms: los perfiles tienen ~1,4 ms de CPU por muestra (la suma de muestras x 1 ms queda ~25-30 % por debajo de la CPU
  medida): el desglose se reparte por **proporciones** de muestras y se escala a la CPU medida con `/proc`. El tiempo de núcleo
  (`write`, `epoll`) se atribuye al marco Java que llamó.
- Las categorías son una heurística de "hoja hacia la raíz" (reglas en `profile-breakdown.py`); las fronteras entre Spring MVC,
  Jackson y los controladores (los récords se leen por `MethodHandle`) son aproximadas.
- El motor corrió como jar en el host con Corretto 25.0.4.1, no en la imagen (Temurin 25, límites de cgroup); el JIT y el GC son los
  mismos en lo esencial pero el heap (`-Xmx768m`) y los 2 núcleos se fijaron a mano.
- k6, NATS y el motor comparten una máquina y la red es loopback: el costo de red/syscalls no es el de producción.
- JWT medido solo a 1.200/s (a 4.000/s se satura); los tokens se reutilizan (35.000 tokens en ~280.000 solicitudes), pero el motor no tiene caché y
  verifica la firma en cada solicitud, así que el costo medido no depende de esa reutilización.
- No se midió la latencia con JWT en estado estable fuera de las ventanas de profiler (p99 de 141 ms y máximo de 5 s de la corrida C
  no se investigaron).
- El costo de la firma en otra CPU, ni librerías alternativas de Ed25519, ni una caché de tokens: no se midieron.

## Archivos

- Gráficos de llama (HTML, interactivos), en `bench/results/profile/`: `sinjwt-4000_{cpu,alloc,wall}.html` (A), `sinjwt-1200_*` (B),
  `jwt-1200_*` (C). Los `.jfr` y `.collapsed` crudos (el `collapsed` de CPU pesa ~30 MB) quedaron fuera del repo.
- `bench/profile-server.sh`, `bench/profile-tokens.py`, `bench/profile-breakdown.py` y el soporte de tokens en `bench/load-pico.js`.
