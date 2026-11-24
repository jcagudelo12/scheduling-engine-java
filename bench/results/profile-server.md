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
- **Actualización:** con Ed25519 de BouncyCastle y rechazos baratos antes de la firma, la firma baja de ~790 a ~210 us por solicitud
  y el costo total de 1.114 a 601 us; ver [Después de optimizar el token](#después-de-optimizar-el-token).

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

## Después de optimizar el token

Cambios (ADR 0004, nota de revisión del 2026-11-22): la firma Ed25519 se verifica con `org.bouncycastle.math.ec.rfc8032.Ed25519`
(bcprov-jdk18on 1.87) en lugar de `java.security.Signature`; la llave pública se decodifica y valida una sola vez al arrancar
(sin `getInstance`/`initVerify` por solicitud); y los rechazos baratos (formato, `alg`, `kid`, longitud de la firma, `exp`, `nbf`,
`iat`, `iss`, `aud`, `sub`, `courses`) se evalúan antes que la firma. **Cada solicitud sigue verificando su firma: el motor no guarda
estado de autenticación entre solicitudes.** No cambia el formato del token ni el contrato HTTP.

### Microbenchmark (JMH, `JwtVerifyBenchmark`, 1 fork, 3 calentamientos y 5 mediciones de 2 s, una sola ejecución)

Misma máquina y token de 6 cursos. Microsegundos por operación (media ± error del intervalo de confianza al 99,9 % de JMH):

| Qué se mide | us/op |
|---|--:|
| Verificación completa `JwtVerifier`, **antes** (firma con el JDK) | 867 ± 37 |
| Verificación completa `JwtVerifier`, **después** (BouncyCastle) | **97 ± 4** |
| Solo la firma: `Signature` Ed25519 del JDK 25, instancia nueva por llamada | 875 ± 35 |
| Solo la firma: `Signature` Ed25519 del JDK, instancia reutilizada | 806-861 |
| Solo la firma: BouncyCastle Ed25519, llave en bytes | 107 ± 4 |
| Solo la firma: BouncyCastle Ed25519, llave ya decodificada (`PublicPoint`) | 96-97 ± 4 |
| Base64 + JSON de cabecera, claims y firma | 1,8 |
| *Referencia, no implementada:* ES256 (ECDSA P-256) con el JDK | 814 ± 40 |
| *Referencia, no implementada:* ES256 con BouncyCastle (`ECDSASigner`) | 99 ± 2 |

La firma del JDK 25 es ~9 veces más lenta que la de BouncyCastle, y **ES256 no ayudaría**: con el JDK cuesta lo mismo que Ed25519
(814 us contra 875 us) y con BouncyCastle lo mismo (99 us contra 97 us). Cambiar de algoritmo con la institución no ahorraría CPU;
lo que se gana es la implementación. Reutilizar el objeto `Signature` del JDK tampoco ayuda (-1 a -8 %). El trabajo que no es criptografía
(Base64 y JSON) son ~2 us: no merece optimizarse.

### Carga con JWT (`AUTH=jwt bench/profile-server.sh`, mismo entorno y datos que §1: 1 réplica con 2 núcleos, perfil `itm`)

Corrida a 1.200/s (+100/s de sonda) con las mismas ventanas que la corrida C (meseta de 235 s):

| | C, antes (JDK) | **Después (BouncyCastle)** |
|---|--:|--:|
| CPU por solicitud, ventana `cpu` (con profiler) | 1.114 us | **601 us** |
| CPU por solicitud, ventana `base` (sin profiler) | 1.128 us | **638 us** (24,99 s de CPU en 39.179 solicitudes) |
| Firma Ed25519 | 786 us (70,6 % de la CPU) | **207 us (34,4 %)** |
| Decodificación y claims (Base64 + JSON) | 10 us | 19 us (3,2 %) |
| Bytes asignados por solicitud (`alloc`) | 104.314 B (firma: 51.439 B) | **57.154 B** (firma: 5.773 B; decodificación 5.800 B) |
| k6, meseta: p50 / p95 / p99 / máx. | 1,3 ms / 60 ms / 141 ms / 5,1 s | **0,79 ms / 1,10 ms / 2,08 ms / 56 ms** |
| Errores | 0 % | 0 % (282.002 solicitudes) |

La firma pasa de 786 a 207 us por solicitud (3,8 veces menos), no a los 97 us del JMH. El resto de la solicitud (lo que no es firma) quedó en
~394 us contra ~328 us antes (1.114 - 786). *Nota posterior (ver "Por qué se desborda la latencia con token", apartado 6):* ambas diferencias son el efecto
de medir a 1.200/s, con los núcleos al 40 % de uso y a ~2,2 GHz de media; a 4.000-5.000/s la firma cuesta 103-111 us y el resto ~245 us. Los hilos hermanos
del motor no son los de k6 (son NATS, la ingesta y la ráfaga de cupos), y de todos modos no influyen.

**Tasa sostenible con token.** Cada punto es una corrida aparte (rampa 20 s, meseta con 30 s de calentamiento, ventana `base` de 45 s sin
profiler y solo 2 s de profiler para no ensuciar la latencia; una sola ejecución por punto). La p99 de k6 es la de toda la meseta
(incluye el calentamiento de la JVM y la ráfaga de cupos de 6/s). Los 100/s de la sonda de E1 no están en la tasa nominal.

| Tasa nominal | Solicitudes/s medidas (`base`) | CPU de los 2 núcleos | CPU por solicitud | p50 / p95 / p99 (k6) | Errores |
|--:|--:|--:|--:|---|--:|
| 1.200 | 1.303 | 42 % | 638 us | 0,79 / 1,10 / 2,08 ms | 0 % |
| 2.000 | 2.103 | 45 % | 425 us | 0,51 / 1,17 / **5,9 ms** | 0 % |
| 2.500 | 2.605 | 51 % | 394 us | 0,47 / 3,7 / 19,9 ms | 0 % |
| 3.000 | 3.104 | 54 % | 351 us | 0,46 / 2,3 / 31,7 ms | 0 % |
| 3.500 | 3.608 | 74 % | 411 us | 0,73 / 839 / 957 ms | 0 % |
| 4.000 | 4.110 | 67 % | 328 us | 0,45 / 103 / 298 ms | 0 % |
| 4.500 | 4.607 | 75 % | 325 us | 0,47 / 354 / 451 ms | 0 % |
| 5.000 | 5.026 | 85 % | 337 us | 0,56 / 209 / 599 ms | 0 % |

- **La tasa máxima con p99 de pocos ms es ~2.000 solicitudes/s por réplica de 2 núcleos** (p99 5,9 ms); a 2.500/s la p99 ya es 20 ms y
  a 3.000/s, 32 ms. Antes la corrida a 1.200/s tenía p99 de 141 ms y la saturación llegaba a ~1.700/s (1.170 us por solicitud): ahora a
  1.200/s la p99 es 2 ms y a 2.000/s sigue en 6 ms. Sin errores en ningún punto (el motor no rechaza, encola).
- A partir de ~3.500/s la latencia se desborda (p95 de cientos de ms) aunque los 2 núcleos no están saturados (70-85 % *de los 2 núcleos*, 1,35-1,7 núcleos).
  *Nota posterior:* **no es un bloque de la firma ni falta de portadores: es el arranque en frío frente a un pico abrupto** (durante ~30 s el compilador C2 se lleva
  más de la mitad de un núcleo y la capacidad es menor que la tasa), y esta tabla mezclaba esa fase con la meseta. En estado estable la misma réplica sostiene
  4.000-4.500/s con p99 de ~19 ms. Ver "Por qué se desborda la latencia con token" más abajo, con repeticiones, evidencia y recomendación.
- Sin token (informe del pico) la misma réplica sostenía ~5.000/s con p99 de unos pocos ms: con token ya no se pierde el 70 % de la capacidad,
  pero aún queda por debajo de la de sin token (el 34 % de la CPU es la firma).
- Para dimensionar con token, usar ~2.000/s por réplica de 2 núcleos como capacidad con latencia baja (antes, a 1.200/s la p99 ya era de 141 ms).
  *Nota posterior:* con la réplica caliente son 4.000-4.500/s, y con un arranque en frío abrupto ~2.500/s (ver la sección siguiente).

### Lo que no se verificó

- Una sola ejecución por punto; el ruido entre ventanas ronda ±7 %. Las filas de más de 3.000/s son indicativas (la sección siguiente las repite 3 veces).
- Los tokens se firmaron con 900 s de vida y se reutilizan (un token por cuerpo de `students.json`/`alternatives.json`): como no hay estado entre
  solicitudes, el costo no depende de esa reutilización.
- La CPU y la latencia de producción (otra CPU, Traefik delante, red real) no se midieron.

## Por qué se desborda la latencia con token

Pregunta: con `AUTH=jwt`, una réplica de 2 núcleos pasaba de p99 de ~6 ms (2.000/s) a cientos de ms o más de 1 s a partir de ~3.500/s, con el
67-85 % de CPU, mientras que sin token sostenía ~5.000/s. Esta sección reproduce el barrido con repeticiones, instrumenta el motor, prueba
hipótesis una por una y responde las dos preguntas que quedaron abiertas en la sección anterior. Todo se midió el 2026-11-22 en la misma
máquina y con los mismos datos que §1, con los scripts nuevos `bench/overflow-run.sh`, `bench/overflow-summary.py` y
`bench/LatencyProbe.java` (ver "Método").

### Resumen

1. **La causa es el arranque en frío frente a un pico abrupto, en una réplica de 2 núcleos; no es un defecto de la firma, de los portadores
   de hilos virtuales, del GC, de k6 ni de los hilos hermanos.** Durante los primeros ~30 s de carga (la rampa de 20 s más ~10 s) el compilador
   C2 consume 0,55-0,65 de los 2 núcleos, los portadores se quedan con ~1,1 y la capacidad es menor que la tasa de llegada: las solicitudes se
   acumulan **antes** de entrar al filtro de la aplicación (en la cola de tareas del planificador de hilos virtuales y en los búferes de los
   sockets), por eso el `LatencyTimer` del motor casi no lo ve. En cuanto el JIT termina, **la misma réplica con la misma tasa queda al 65 %
   de CPU y con p99 de 6-20 ms**. Certeza: alta en el mecanismo (cuatro evidencias independientes, abajo); media en atribuirlo solo al C2
   (no hay una ablación limpia: quitar C2 con `-XX:TieredStopAtLevel=1` empeora todo porque el estado estable cuesta el doble).
2. **El "67-85 % de CPU" del barrido era el valor del estado estable, y es del conjunto de los 2 núcleos** (1,35-1,7 núcleos en uso), no de
   uno. Durante el desborde la CPU estaba al 100 % de los 2 núcleos (JFR: 2,0 núcleos en los primeros 20 s de carga). El barrido original
   mezclaba el arranque en frío con la meseta (su p99 era el de toda la meseta, que empezaba en el segundo 20 de la rampa).
3. **Capacidad en estado estable con token (réplica de 2 núcleos, calentada):** 4.000/s con p99 de ~12 ms, 4.500/s con ~19 ms, 5.000/s con ~53 ms
   (G1) y saturada a 5.500/s (CPU al 93 %, p95 de 133 ms). No hay un bloque no cedible de la firma que limite antes: a 4.500/s la firma cuesta
   ~105 us y el p99 sigue siendo de ~19 ms.
4. **Lo que sí se puede absorber sin desbordarse con un arranque en frío abrupto son ~2.500/s** (p99 de 3 ms, 0 iteraciones descartadas,
   3 de 3 corridas); a 3.000/s ya hay desborde en la fase fría (p99 de calentamiento de entre 90 ms y 3,5 s según la corrida). Con una rampa de 120 s en vez de 20 s, 3.500/s no se
   desborda (0 descartadas, p99 de 5 ms): lo que falla es la velocidad con que llega la carga a una JVM fría.
5. **Arreglo de configuración aplicado:** recolector Serial explícito en la imagen (`-XX:+UseSerialGC` en el `ENTRYPOINT` del `Dockerfile`): -12 % de
   CPU por solicitud a 4.000/s (314 contra 355 us, rangos disjuntos en 3 + 3 corridas) y p99 en estado estable de 18 ms a 5.000/s contra 53 ms con G1.
   No arregla el arranque en frío. El resto del arreglo es de código (precalentamiento) o de operación (más núcleos o rampa gradual): ver "Recomendación".
6. **Preguntas secundarias:** (a) los 207 us de la firma contra los 97 us de JMH eran el efecto de medir a baja utilización (1.200/s = 40 % de CPU, núcleos
   a ~2,2 GHz de media): a 4.000-5.000/s la misma firma cuesta 103-111 us, y un microbenchmark sin red reproduce 106 us al 100 % de uso y 216-254 us
   al 15-30 %. No eran los hilos hermanos ni k6. (b) Los ~394 us del resto de la solicitud contra ~328 us eran el mismo efecto del punto de operación, no una
   regresión del cambio a BouncyCastle: a 4.000-5.000/s el resto cuesta 241-246 us.

### Método

- **Corrida** (`bench/overflow-run.sh`): lo mismo que `profile-server.sh` (jar en el host, `taskset` 4,6, `-Xmx768m`, G1 salvo que se indique, perfil `itm`,
  NATS e ingesta en 5,7, ráfaga de cupos en 7, k6 en 8-15, JWT EdDSA), sin profiler y con parámetros para cada experimento. Rampa de 20 s
  y meseta de 30 s de calentamiento + 60 s medidos, salvo que se indique ("calentamiento" 100 s en las filas de estado estable). Cada corrida arranca todo de
  cero (NATS, ingesta, catálogo, motor) y lo detiene al terminar.
- **k6** (`bench/load-pico.js`, cambios compatibles): `WARM` (segundos de la meseta etiquetados `warm`: quedan fuera de `phase:hold`; 0 por defecto) y `SLICE_S`
  (etiqueta `slice` por tramos de N s desde `START_MS`, para ver la latencia en el tiempo). Las columnas p50/p95/p99 son de `phase:hold` (los 60 s medidos, después
  de rampa + calentamiento, es decir, desde el segundo 50); "p99 calentamiento" es el de `phase:warm` (segundos 20-50). Las "descartadas" son `dropped_iterations` de toda la corrida.
- **CPU**: por hilo (`/proc/<pid>/task/*/stat` al inicio y al final de la ventana, agrupado por nombre de hilo), por núcleo (`/proc/stat`, para detectar CPU ajena en 4 y 6;
  fue ≤ 0,09 núcleos en todas las corridas) y de k6 (`/proc/<pid>/stat` de `k6`).
- **Sin lecturas de Prometheus durante la ventana** (`SNAP=noprom`, por defecto): la primera versión leía `/actuator/prometheus` al inicio de la ventana y eso, junto con el
  sondeo de `jcmd`, frenaba la réplica entre cientos de ms y 2 s justo al empezar la ventana (se vio en 4 corridas con la lectura y no en 2 sin ella; ver "Lo que no se explica").
  Las solicitudes por segundo y los us por solicitud salen de los contadores de k6 (+100/s de la sonda de E1, que no usa token y no entra en `phase:hold`).
- **Sonda independiente** (`bench/LatencyProbe.java`, `PROBE=1`): cliente propio de bucle abierto (mide desde la hora programada y desde el envío), 200/s, 16 hilos, núcleos 0-3.
- **Estadística**: 3 corridas por punto en la tabla base y 2-3 en los experimentos, media ± desviación estándar de la muestra y, para el p99, el mínimo-máximo.
  Con n = 2-3 las diferencias solo cuentan cuando los rangos no se solapan.
- Datos crudos (k6 `.json`, hilos, sockets, JFR, perfiles) en el directorio temporal de la sesión; no se versionan.

### 1. Reproducción con repeticiones

Tasa nominal de k6 (a la que se suman 100/s de la sonda de E1); 3 corridas por punto, rampa de 20 s, calentamiento de 30 s y 60 s medidos. "máx." = `maxVUs` de k6 (`min(10000, max(500, 2 x tasa))`).
Latencias en ms; "descartadas" = `dropped_iterations` de la corrida entera; "VUs usados" = `vus_max`.

| Config | n | p50 ms | p95 ms | p99 ms | p99 mín-máx | p99 calentamiento ms | descartadas | VUs usados | CPU k6 (núcleos) | CPU motor (% de 2) | us CPU/solicitud |
|---|--:|--:|--:|--:|--:|--:|--:|--:|--:|--:|--:|
| 2.500/s (máx. 5000) | 3 | 0,46 ± 0,02 | 1,3 ± 0,1 | 3,0 ± 0,2 | 2,8-3,2 | 11 ± 7 | 0 ± 0 | 270 ± 0 | 1,83 ± 0,05 | 48 ± 1 | 369 ± 7 |
| 3.000/s (máx. 6000) | 3 | 0,44 ± 0,02 | 3,4 ± 3,6 | 9,0 ± 10,2 | 3,0-20,8 | 1.281 ± 1.931 | 3.648 ± 3.987 | 839 ± 508 | 2,07 ± 0,13 | 57 ± 4 | 367 ± 29 |
| 3.500/s (máx. 7000) | 3 | 0,44 ± 0,02 | 5,9 ± 3,7 | 15,7 ± 8,5 | 6,1-22,3 | 318 ± 244 | 6.300 ± 7.930 | 1.227 ± 1.025 | 2,29 ± 0,13 | 65 ± 3 | 361 ± 18 |
| 4.000/s (máx. 8000) | 3 | 0,45 ± 0,02 | 3,9 ± 1,6 | 17,8 ± 9,9 | 9,3-28,7 | 1.463 ± 890 | 12.713 ± 2.872 | 2.109 ± 420 | 2,43 ± 0,04 | 72 ± 2 | 355 ± 10 |

La latencia de la meseta medida (desde el segundo 50) es sana en todos los puntos (p99 de 3 a 18 ms, p50 de 0,45 ms). **El desborde está en la fase fría**, y solo se ve
mezclando las fases. Latencia por tramos de 10 s (p50 / p95 en ms, media de las 3 corridas; la rampa va de la décima parte de la tasa a la tasa entera en los primeros 20 s):

| Tramo de 10 s desde el inicio de la rampa | 2.500/s | 3.000/s | 3.500/s | 4.000/s | 3.500/s con 4 núcleos |
|--|--:|--:|--:|--:|--:|
| 0-10 | 4,2 / 161 | 88,5 / 375 | 204,7 / 424 | 441,8 / 663 | 0,9 / 4 |
| 10-20 | 1,3 / 31 | 152,6 / 443 | 260,3 / 955 | 483,1 / 694 | 0,5 / 2 |
| 20-30 | 0,5 / 7 | 91,7 / 808 | 119,0 / 285 | 254,7 / 1.093 | 0,5 / 2 |
| 30-40 | 0,5 / 1 | 0,4 / 4 | 0,4 / 6 | 0,5 / 11 | 0,5 / 2 |
| 40-50 | 0,5 / 1 | 0,4 / 3 | 0,4 / 8 | 0,5 / 3 | 0,5 / 2 |
| 50-60 | 0,5 / 1 | 0,4 / 3 | 0,4 / 7 | 0,5 / 8 | 0,5 / 1 |
| 60-70 | 0,5 / 1 | 0,4 / 2 | 0,4 / 5 | 0,4 / 3 | 0,5 / 1 |
| 70-80 | 0,5 / 1 | 0,4 / 4 | 0,4 / 5 | 0,5 / 7 | 0,5 / 1 |
| 80-90 | 0,5 / 1 | 0,4 / 3 | 0,4 / 6 | 0,4 / 4 | 0,5 / 2 |
| 90-100 | 0,5 / 1 | 0,4 / 3 | 0,5 / 7 | 0,4 / 5 | 0,5 / 1 |
| 100-110 | 0,5 / 1 | 0,4 / 6 | 0,5 / 5 | 0,4 / 2 | 0,5 / 1 |
| 110-120 | 0,5 / 1 | 0,5 / 3 | 0,5 / 2 | 0,4 / 1 | 0,5 / 2 |

- Hasta 2.500/s el arranque en frío se absorbe (p95 de 161 ms en los primeros 10 s y 7 ms en el tercer tramo). Desde 3.000/s la mediana de la latencia llega a cientos de ms
  en los primeros 30 s y el p95 a ~1 s. Después del segundo 30 todas las tasas quedan en p50 de 0,4-0,5 ms, y el p95 depende de la tasa (1 ms a 2.500/s, 2-8 ms a 3.000-4.000/s).
- Con 4 núcleos (mismo motor, 3.500/s) no hay fase fría: p95 de 4 ms en los primeros 10 s y 2 ms después, 0 iteraciones descartadas, CPU del 36 %.
- El p99 de 957 ms del barrido original a 3.500/s (y el p95 de 839 ms) era esta fase, no el estado estable: el p99 "de toda la meseta" empezaba en el segundo 20.

### 2. k6 no es el cuello de botella, y la latencia medida no incluye espera del cliente

| Comprobación | Resultado |
|---|---|
| CPU de k6 | 1,8-2,4 núcleos de los 4 físicos (8 hilos) hasta 4.000/s con fase fría; 3,0-3,5 con la réplica ya calentada a 4.000-5.500/s. En el desborde (3.500/s, 2,3 núcleos) k6 tiene holgura; a 5.500/s ya está cerca de su techo, pero el motor llega antes (CPU al 92-93 %, p50 de 7 ms) |
| VUs | 270-2.109 usados de 5.000-8.000 permitidos hasta 4.000/s (~3.400 de 10.000 a 5.500/s): nunca se agotan |
| `dropped_iterations` | 0 con 2.500/s y con 4 núcleos o rampa de 120 s; 3.600-12.700 de media por punto (3.000-4.000/s) en las corridas con fase fría. Aparecen cuando la latencia sube a segundos y k6 necesita más VUs de los reservados (350-400) que no puede arrancar a tiempo: son consecuencia del desborde, no su causa. Como k6 descarta esas iteraciones en vez de lanzarlas tarde, **la latencia de la fase fría está subestimada** |
| Latencia con otro cliente | `LatencyProbe` (200/s, núcleos 0-3, bucle abierto), a 3.500/s en la misma ventana: desde el envío p95 de 76 ms y p99 de 110 ms contra p95 de 78 ms y p99 de 114 ms de k6; desde la hora programada p95 de 244 ms y p99 de 411 ms (la espera del hilo cliente, que k6 no cuenta) |
| Tiempos de k6 | En la corrida del barrido original a 3.500/s, `http_req_blocked` (p99 0,17 ms) y `http_req_connecting` (p99 0,12 ms) eran despreciables: la latencia es la del servidor y la red local; el cliente no está esperando una conexión |
| Cola del lado del cliente | `Recv-Q` del lado del cliente con datos en 25 de 164 muestras (máximo de 6,7 KB, una conexión): k6 lee las respuestas a tiempo; la cola está del lado del motor (§3) |

### 3. Instrumentación del motor

**GC** (JFR, 60 s medidos a 3.500/s, G1): 211 recolecciones, suma de pausas de 689 ms (1,15 % del tiempo), pausa máxima de 13 ms. **Safepoints**: 264, 11 ms en total, máximo de 1,1 ms.
**`jdk.VirtualThreadPinned`**: 0 eventos en la ventana (20 en toda la grabación, todos en el primer segundo de carga: monitores contendidos y carga de clases en el `catalog-follower`, Tomcat y Jackson, de 0,1 a 24 ms).
**`jdk.VirtualThreadSubmitFailed`**: 0. Con Serial a 4.000/s: 119 recolecciones, 351 ms (0,59 %), máximo de 6,9 ms; con G1 a 4.000/s: 204, 801 ms (1,32 %), máximo de 10,3 ms. Ninguna pausa explica latencias de 100 ms a segundos.

**CPU por hilo** (estado estable, 3.500/s, media de 3 corridas, en núcleos de los 2): portadores de hilos virtuales (`ForkJoinPool-1`) 1,12 (dos portadores a 0,54 cada uno y un tercero ocioso),
Tomcat (poller y utilitarios) 0,09, GC y VM 0,07, JIT 0,01, seguidor de NATS 0,001. A 5.000/s: portadores 1,47, Tomcat 0,12. Los portadores nunca llegan al 100 % individual en estado estable;
la CPU ajena en 4 y 6 fue ≤ 0,09 núcleos. El endpoint de Prometheus no publica métricas de Tomcat (solo sesiones), y no se activó `tomcat.mbeanregistry` porque cambia el costo de la solicitud.

**Fase fría, planificador y sockets** (una corrida a 3.500/s con `jcmd Thread.vthread_scheduler` cada segundo y `ss` cada 0,25 s desde el inicio de la rampa; el sondeo también ocupa CPU,
así que los valores son cualitativos). Con los dos portadores activos y `tasks = 0`, la cola de **envíos externos** del planificador (las tareas de arranque de hilos virtuales que
crea el poller de Tomcat) crece, y los sockets del motor tienen solicitudes sin leer:

| Segundos desde el inicio de la rampa | `submissions` en cola | Conexiones con bytes sin leer / total | Bytes sin leer en los sockets |
|--:|--:|--:|--:|
| 1,0 | 175 | 162 / 350 | 118 KB |
| 12,6 | 241 | 263 / 350 | 191 KB |
| 15,0 | 251 | 289 / 399 | 214 KB |
| 20,8 | 299 | 284 / 430 | 201 KB |
| 22,0 | 316 | 164 / 451 | 122 KB |
| 24,4 | 100 | 22 / 452 | 18 KB |
| 26,7 en adelante | 0-4 | 0 / ~450 | 0 |

(cada solicitud pesa ~0,7 KB: 290 conexiones con datos son ~290 solicitudes esperando antes de que la aplicación las lea, ~80 ms de trabajo a 3.500/s.) Del segundo 27 en adelante la cola
es 0 y los sockets están vacíos. Es la señal de saturación de CPU con los portadores ocupados, no de un bloqueo: ningún hilo estaba fijado (pinned).

**Tiempo del lado del servidor contra k6.** En la corrida base de 3.500/s, el histograma `sched_http_latency_seconds` de `/combinations` (acumulado desde el arranque, incluida la fase fría) tiene
289.180 solicitudes, de las cuales solo 98 (0,034 %) tardaron más de 10 ms dentro del filtro y 4 más de 200 ms, ninguna más de 0,5 s; en el mismo tiempo k6 midió un p95 de 400-900 ms en los
primeros 30 s. En la ventana medida de otra corrida a 3.500/s, con p95 de 78 ms en k6 y en la sonda, la fracción de solicitudes con más de 10 ms en el filtro fue 0,00 %. **La espera ocurre antes
del `HttpMetricsFilter`** (cola del planificador, `Recv-Q` y análisis de cabeceras) y el `LatencyTimer` no la puede ver. Para medirla en producción haría falta un temporizador anterior
(p. ej. en el `Valve` de Tomcat) o medir en Traefik.

**CPU del arranque en frío** (async-profiler `ctimer`, 1 ms, 40 s desde el inicio de la rampa a 3.500/s, JFR cortado por tramos de 10 s). Núcleos de CPU por categoría de hilo (con el profiler activo, que suma su propio costo):

| Tramo | CPU total | Portadores | Compilador C2 | Compilador C1 | En portadores: intérprete / C1 / firma |
|--|--:|--:|--:|--:|--:|
| 0-10 s | 1,82 | 1,06 | 0,55 | 0,16 | 6,2 % / 32,9 % / 13 % |
| 10-20 s | 1,85 | 1,15 | 0,65 | 0,00 | 0,6 % / 14,7 % / 26 % |
| 20-30 s | 1,36 | 0,93 | 0,36 | 0,01 | 0,3 % / 2,0 % / 34 % |
| 30-40 s | 0,87 | 0,77 | 0,02 | 0,00 | 0,1 % / 0,6 % / 35 % |

Durante los primeros 20 s el compilador C2 (un solo hilo con 2 CPU) ocupa 0,55-0,65 de los 2 núcleos y deja a los portadores ~1,1, cuando en estado estable necesitan 1,2-1,3 a esta tasa (y todavía con
código C1 e intérprete: el 39 % y el 15 % de las muestras de los portadores en los dos primeros tramos). JFR confirma el 100 % de CPU: 2,0 núcleos de la JVM en los primeros 20 s de carga, 1,6 en los 10 s siguientes y 1,3 después, con al menos 3,3 y 5,1 s de
compilación (eventos de más de 100 ms) en los dos primeros tramos de 10 s. Los métodos que más tarda en compilar C2 (hasta 0,8 s cada uno) son de Spring MVC, Jackson, Tomcat, el solver y la firma de BouncyCastle (`scalarMultStraus128Var`, 0,67 s), y siguen
compilándose durante ~40 s.


### 4. Hipótesis y experimentos controlados

Todos a 3.500/s (el punto de desborde), 2-3 corridas, con las mismas condiciones que la tabla base. Un solo cambio por fila. Filas `base_3500` y `h2_serial` con n = 3; las demás n = 2.

| Fila | Qué cambia |
|---|---|
| `base_3500` | referencia (G1, 2 núcleos, hilos virtuales con 2 portadores) |
| `h1_par4` | `-Djdk.virtualThreadScheduler.parallelism=4` y `maxPoolSize=4` (H1) |
| `h1_pt50`, `h1_pt200` | `spring.threads.virtual.enabled=false`, `server.tomcat.threads.max` = 50 / 200 (H1) |
| `h2_zgc`, `h2_serial`, `h2_heap1g` | `-XX:+UseZGC` (generacional en JDK 25), `-XX:+UseSerialGC`, heap de 1 g en vez de 768 m (H2) |
| `h3_4cores` | motor en 4,6,8,10 (4 núcleos físicos), k6 en 0-3 y 12-15 (H3) |
| `h3_ht` | motor en 4,6 con los hermanos 5 y 7 libres: NATS, ingesta y ráfaga de cupos pasan a 0,2 (H3, hilos hermanos) |
| `h4_nostorm` | sin ráfaga de cupos (H4); también desaparece la sonda de E1 (-100/s) |
| `jit_c1` | `-XX:TieredStopAtLevel=1` (solo C1) |

| Config | n | p50 ms | p95 ms | p99 ms | p99 mín-máx | p99 calentamiento ms | descartadas | VUs usados | CPU k6 (núcleos) | CPU motor (% de 2) | us CPU/solicitud |
|---|--:|--:|--:|--:|--:|--:|--:|--:|--:|--:|--:|
| base_3500 | 3 | 0,44 ± 0,02 | 5,9 ± 3,7 | 15,7 ± 8,5 | 6,1-22,3 | 318 ± 244 | 6.300 ± 7.930 | 1.227 ± 1.025 | 2,29 ± 0,13 | 65 ± 3 | 361 ± 18 |
| h1_par4 | 2 | 3,78 ± 4,71 | 60,2 ± 82,7 | 209,3 ± 290,1 | 4,2-414,5 | 9.992 ± 3.909 | 26.216 ± 9.163 | 3.654 ± 641 | 2,05 ± 0,11 | 72 ± 9 | 402 ± 49 |
| h1_pt50 | 2 | 558,29 ± 783,72 | 1.540,8 ± 80,0 | 1.727,0 ± 248,8 | 1.551,0-1.902,9 | 1.755 ± 89 | 41.463 ± 2.490 | 5.006 ± 257 | 2,24 ± 0,00 | 92 ± 8 | 518 ± 52 |
| h1_pt200 | 2 | 1.050,92 ± 1.456,51 | 1.936,9 ± 757,7 | 2.892,5 ± 1.071,3 | 2.135,0-3.650,0 | 3.479 ± 797 | 60.684 ± 20.942 | 6.104 ± 1.409 | 2,24 ± 0,03 | 94 ± 6 | 550 ± 70 |
| h2_zgc | 2 | 0,46 ± 0,04 | 2,5 ± 0,4 | 7,4 ± 1,1 | 6,6-8,2 | 570 ± 219 | 8.634 ± 3.547 | 1.788 ± 563 | 2,06 ± 0,08 | 67 ± 3 | 369 ± 14 |
| h2_serial | 3 | 0,46 ± 0,04 | 2,6 ± 1,8 | 8,3 ± 5,6 | 4,5-14,8 | 557 ± 402 | 8.014 ± 4.852 | 1.565 ± 749 | 2,30 ± 0,33 | 63 ± 3 | 349 ± 19 |
| h2_heap1g | 2 | 0,45 ± 0,00 | 4,2 ± 2,6 | 12,1 ± 6,9 | 7,2-17,0 | 2.557 ± 1.700 | 8.252 ± 2.767 | 1.536 ± 517 | 2,51 ± 0,40 | 63 ± 0 | 352 ± 2 |
| h3_4cores | 2 | 0,45 ± 0,00 | 1,4 ± 0,3 | 3,2 ± 0,1 | 3,1-3,2 | 4 ± 1 | 0 ± 0 | 370 ± 0 | 2,00 ± 0,05 | 36 ± 1 | 394 ± 8 |
| h3_ht | 2 | 0,49 ± 0,06 | 3,3 ± 2,5 | 10,4 ± 7,0 | 5,5-15,4 | 542 ± 56 | 7.624 ± 491 | 1.524 ± 102 | 2,22 ± 0,36 | 67 ± 5 | 372 ± 29 |
| h4_nostorm | 2 | 0,46 ± 0,01 | 7,3 ± 5,2 | 17,9 ± 8,9 | 11,6-24,2 | 1.222 ± 232 | 6.260 ± 1.698 | 3.406 ± 778 | 1,72 ± 0,12 | 66 ± 4 | 375 ± 21 |
| jit_c1 | 2 | 2.706,38 ± 15,66 | 2.870,6 ± 47,0 | 3.315,4 ± 463,2 | 2.987,9-3.643,0 | 4.224 ± 3.421 | 96.737 ± 5.173 | 7.100 ± 0 | 2,38 ± 0,17 | 98 ± 2 | 752 ± 9 |

Lectura (cada hipótesis, con lo que descarta o confirma):

- **H1, portadores insuficientes: descartada.** Con 4 portadores en 2 núcleos (`h1_par4`) el resultado es peor o igual (p99 de calentamiento de 7-13 s, 26.000 iteraciones descartadas): más portadores
  que núcleos solo añaden cambios de contexto. Con hilos de plataforma (50 o 200 hilos de Tomcat) es mucho peor: 481-600 us de CPU por solicitud (contra 360), CPU al 86-99 % y una fase fría de 80-90 s
  en vez de 30 s. Los portadores en estado estable están al 54 % cada uno y la cola de tareas del planificador es 0 una vez calentado.
- **H2, GC: no es la causa del desborde.** Las pausas son de ≤ 13 ms y suman ~1 % del tiempo (§3). ZGC (p99 de 7,4 ms, calentamiento de 570 ms) y 1 g de heap (p99 de 12 ms, calentamiento de 2,6 s) no mejoran la fase fría
  frente a G1. **Serial sí mejora el estado estable** (ver abajo) pero tampoco elimina la fase fría.
- **H3, límite físico: confirmada.** Con 4 núcleos el mismo motor a la misma tasa no tiene fase fría (p99 de calentamiento de 4 ms, 0 descartadas, CPU del 36 %): es capacidad de CPU de los 2 núcleos mientras el JIT trabaja, no un defecto
  de la aplicación. **Hilos hermanos: descartado.** Con los hermanos de los núcleos del motor libres (`h3_ht`) no hay diferencia (p99 de 10,4 ± 7,0 contra 15,7 ± 8,5 ms; 372 contra 361 us por solicitud). La topología es:
  los hilos lógicos (4,5), (6,7) son hermanos; el motor usa 4 y 6, y sus hermanos 5 y 7 los usan NATS, la ingesta y la ráfaga (a 3.500/s: 7 % de CPU cada uno); k6 usa los núcleos físicos 4-7 (hilos 8-15), que **no** son
  hermanos de los del motor. La explicación de la sección anterior ("el motor comparte núcleos físicos con k6") era incorrecta.
- **H4, seguidor de NATS y ráfaga de cupos: descartada.** Sin ráfaga (`h4_nostorm`) el resultado es el mismo (p99 de 17,9 ± 8,9 ms, fase fría igual): el seguidor de NATS gasta 0,001 núcleos.
- **Solo C1** (`jit_c1`): el estado estable cuesta 752 us por solicitud (el doble), la CPU llega al 98 % y la latencia no se recupera (p50 de 2,7 s): C2 es imprescindible en estado estable, no se puede quitar para evitar su costo en frío.
- **Rampa gradual:** con 120 s de rampa en vez de 20 s (`ramp120`, 3.500/s) no hay desborde: 0 descartadas, p99 de calentamiento de 7 ms y p99 medido de 5,1 ms. La JVM se calienta mientras la tasa sube.

### 5. Tasa sostenible en estado estable, con token

Mismas condiciones, pero con 100 s de calentamiento (150 s de carga antes de medir, así que la fase fría ya pasó), 2 corridas por punto, G1; al final, Serial a 5.000/s. `low_*` son las corridas de baja tasa del apartado 6.

| Config | n | p50 ms | p95 ms | p99 ms | p99 mín-máx | p99 calentamiento ms | descartadas | VUs usados | CPU k6 (núcleos) | CPU motor (% de 2) | us CPU/solicitud |
|---|--:|--:|--:|--:|--:|--:|--:|--:|--:|--:|--:|
| low_1200 | 2 | 0,81 ± 0,01 | 1,1 ± 0,1 | 4,8 ± 0,2 | 4,7-5,0 | 4 ± 0 | 0 ± 0 | 140 ± 0 | 1,30 ± 0,01 | 40 ± 0 | 619 ± 5 |
| low_2000 | 2 | 0,51 ± 0,01 | 1,0 ± 0,0 | 2,3 ± 0,1 | 2,2-2,4 | 4 ± 0 | 0 ± 0 | 220 ± 0 | 1,90 ± 0,05 | 42 ± 0 | 404 ± 1 |
| serial5000 | 2 | 0,43 ± 0,00 | 4,0 ± 0,3 | 18,1 ± 6,1 | 13,8-22,4 | 488 ± 79 | 15.800 ± 1.592 | 2.388 ± 53 | 3,44 ± 0,02 | 75 ± 0 | 294 ± 1 |
| steady_4000 | 2 | 0,43 ± 0,02 | 4,5 ± 4,8 | 12,4 ± 11,7 | 4,1-20,7 | 147 ± 110 | 7.260 ± 3.029 | 1.260 ± 271 | 3,02 ± 0,03 | 66 ± 4 | 320 ± 20 |
| steady_4500 | 2 | 0,45 ± 0,01 | 5,2 ± 1,6 | 18,6 ± 4,1 | 15,7-21,5 | 455 ± 24 | 11.496 ± 1.743 | 1.932 ± 74 | 3,22 ± 0,05 | 72 ± 1 | 312 ± 7 |
| steady_5000 | 2 | 0,49 ± 0,01 | 25,0 ± 1,3 | 53,0 ± 2,4 | 51,3-54,7 | 542 ± 146 | 23.162 ± 1.598 | 2.800 ± 98 | 3,26 ± 0,08 | 82 ± 1 | 321 ± 5 |
| steady_5500 | 2 | 6,98 ± 4,19 | 133,2 ± 121,3 | 187,6 ± 133,6 | 93,1-282,1 | 698 ± 50 | 34.546 ± 4.695 | 3.135 ± 373 | 3,50 ± 0,07 | 92 ± 1 | 330 ± 2 |

- Una réplica de 2 núcleos calentada y con token sostiene **4.000/s con p99 de ~12 ms y 4.500/s con ~19 ms**; a 5.000/s el p99 sube a ~53 ms (CPU al 82 %) y a **5.500/s se satura** (CPU al 92 %, p95 de 133 ms, p50 de 7 ms: la cola ya no se vacía).
  Esto es consistente con el costo por solicitud: 312-334 us x 5.600/s = 1,8 de los 2 núcleos. Los 4.500/s con ~19 ms de p99 mejoran lo que decía la sección anterior (~2.000/s) porque aquella cifra se medía incluyendo la fase fría.
- Sin token se sostenían ~5.000/s con CPU al 109 % (1,1 núcleos): con token a 5.000/s la CPU es 1,6 núcleos; la firma agrega ~105 us por solicitud (~0,5 núcleos a 5.000/s). La diferencia entre "con" y "sin" token es esa
  CPU, no un bloque no cedible.
- Con Serial a 5.000/s: p95 de 4,0 ms (contra 25,0) y p99 de 18,1 ms (contra 53,0), 294 contra 321 us por solicitud.

### 6. Las dos preguntas secundarias

**(a) La firma costaba ~207 us bajo carga y 97 us en JMH: es el punto de operación (baja utilización), no ruido ni hilos hermanos.** Evidencia:

| Qué | Utilización de los 2 núcleos | Frecuencia media de los núcleos del motor | us de CPU por solicitud | Firma (perfil) |
|---|--:|--:|--:|--:|
| 1.200/s (`low_1200`, 2 corridas) | 40 % | 2,18-2,29 GHz (mínimo 1,27) | 619 ± 5 | 207 us (perfil de la sección anterior) |
| 2.000/s (`low_2000`) | 42 % | 2,56-2,73 GHz | 404 ± 1 | - |
| 3.500/s | 65 % | 3,3-3,4 GHz | 361 ± 18 | - |
| 4.000/s | 66-75 % | 3,38 GHz | 320-355 | 103 us (perfil) |
| 5.000/s | 82 % | 3,36-3,37 GHz | 321 ± 5 | 111 us (perfil) |

El governor es `schedutil` y la CPU baja de ~3,4 a ~1,4 GHz en reposo. A baja utilización los núcleos pasan menos tiempo a frecuencia máxima y salen de estados de reposo con las cachés frías: **todo** cuesta más por solicitud (619 us a 1.200/s
contra 312-355 us por encima de 4.000/s, 1,7-2 veces), no solo la firma. Un microbenchmark sin red ni motor (`BouncyCastle.Ed25519.verify` en un núcleo, ráfagas de 2 ms con distinto factor de uso, `taskset` en el
núcleo 2) lo reproduce: **106 us con el núcleo al 100 %, 124 us al 50 %, 216-243 us al 30 % y 254 us al 15 %** (dos corridas al 100 % dieron 105,5 y 105,9). Los 97 us de JMH corresponden al núcleo al 100 %; los 103-111 us de los perfiles
a 4.000-5.000/s, a la misma situación. Lo que no se pudo separar es cuánto de ese factor 2 es frecuencia y cuánto son estados de reposo y cachés (la lectura de `scaling_cur_freq` es un promedio de 1 s y no lo distingue).
Los hilos hermanos no influyen (H3: `h3_ht`).

**(b) El resto de la solicitud (lo que no es firma) pasó de ~328 a ~394 us: no es una regresión del cambio, es el mismo efecto.** El "antes" (1.114 us a 1.200/s) se midió con la réplica saturada (CPU al 100 %, frecuencia máxima, hilos siempre
ocupados) y el "después" (601 us a 1.200/s) con ella al 40 % (frecuencia baja, hilos que se duermen y despiertan entre solicitudes: las categorías "Tomcat / E/S de red" pasaron de 106 a 164 us y "hilos virtuales" de 7 a 22 us por el costo de `futex` y `epoll`
en cada solicitud). **Comparado a alta utilización (núcleos a frecuencia máxima), el resto cuesta menos que antes**: a 4.000/s es 349 - 103 = 246 us y a 5.000/s es 352 - 111 = 241 us (perfiles de 10 s de la sección anterior), contra los 328 us de "antes"; con Serial, 294 us totales a 5.000/s.
Estos perfiles son de 10 s y el ruido entre ventanas es de ±7 %: la conclusión es que no hay evidencia de regresión, no una medida exacta del resto.

### 7. Lo descartado, en una línea

Hilos virtuales fijados (0 eventos en la ventana), fallos al planificar hilos virtuales (0), pausas de GC (≤ 13 ms, ~1 %), safepoints (≤ 1,1 ms), seguidor de NATS y ráfaga de cupos, k6 (CPU, VUs, descartadas, sonda independiente),
hilos hermanos, CPU ajena en los núcleos del motor (≤ 0,09), más portadores, hilos de plataforma, ZGC, heap mayor, y ES256 u otro algoritmo (la firma no es el factor: cuesta lo mismo en el JDK).

### 8. Recomendación

**Aplicado (solo configuración):** `-XX:+UseSerialGC` en el `ENTRYPOINT` del `Dockerfile` (y `ENGINE_OPTS` en `bench/profile-server.sh` para repetirlo en el host). Mejoras medidas (host, 2 núcleos, G1 contra Serial, mismas condiciones):

| Config | n | p50 ms | p95 ms | p99 ms | p99 mín-máx | p99 calentamiento ms | descartadas | VUs usados | CPU k6 (núcleos) | CPU motor (% de 2) | us CPU/solicitud |
|---|--:|--:|--:|--:|--:|--:|--:|--:|--:|--:|--:|
| G1 2.500/s | 3 | 0,46 ± 0,02 | 1,3 ± 0,1 | 3,0 ± 0,2 | 2,8-3,2 | 11 ± 7 | 0 ± 0 | 270 ± 0 | 1,83 ± 0,05 | 48 ± 1 | 369 ± 7 |
| Serial 2.500/s | 1 | 0,48 | 0,8 | 1,4 | 1,4-1,4 | 3 | 0 | 270 | 2,28 | 46 | 353 |
| G1 3.500/s | 3 | 0,44 ± 0,02 | 5,9 ± 3,7 | 15,7 ± 8,5 | 6,1-22,3 | 318 ± 244 | 6.300 ± 7.930 | 1.227 ± 1.025 | 2,29 ± 0,13 | 65 ± 3 | 361 ± 18 |
| Serial 3.500/s | 3 | 0,46 ± 0,04 | 2,6 ± 1,8 | 8,3 ± 5,6 | 4,5-14,8 | 557 ± 402 | 8.014 ± 4.852 | 1.565 ± 749 | 2,30 ± 0,33 | 63 ± 3 | 349 ± 19 |
| G1 4.000/s | 3 | 0,45 ± 0,02 | 3,9 ± 1,6 | 17,8 ± 9,9 | 9,3-28,7 | 1.463 ± 890 | 12.713 ± 2.872 | 2.109 ± 420 | 2,43 ± 0,04 | 72 ± 2 | 355 ± 10 |
| Serial 4.000/s | 3 | 0,43 ± 0,01 | 1,7 ± 0,9 | 5,9 ± 3,0 | 4,2-9,4 | 431 ± 265 | 8.207 ± 5.996 | 1.550 ± 827 | 2,90 ± 0,31 | 64 ± 4 | 314 ± 20 |
| G1 5.000/s (100 s de calentamiento) | 2 | 0,49 ± 0,01 | 25,0 ± 1,3 | 53,0 ± 2,4 | 51,3-54,7 | 542 ± 146 | 23.162 ± 1.598 | 2.800 ± 98 | 3,26 ± 0,08 | 82 ± 1 | 321 ± 5 |
| Serial 5.000/s (100 s de calentamiento) | 2 | 0,43 ± 0,00 | 4,0 ± 0,3 | 18,1 ± 6,1 | 13,8-22,4 | 488 ± 79 | 15.800 ± 1.592 | 2.388 ± 53 | 3,44 ± 0,02 | 75 ± 0 | 294 ± 1 |
| G1 3.500/s con rampa de 120 s | 2 | 0,45 ± 0,01 | 1,1 ± 0,3 | 5,1 ± 0,8 | 4,5-5,7 | 7 ± 1 | 0 ± 0 | 370 ± 0 | 2,92 ± 0,01 | 56 ± 0 | 310 ± 1 |

- A 4.000/s: **314 ± 20 contra 355 ± 10 us por solicitud** (-12 %, rangos 298-336 y 349-366, disjuntos) y p99 de 5,9 ± 3,0 contra 17,8 ± 9,9 ms; a 5.000/s: p99 de 18,1 contra 53,0 ms y p95 de 4,0 contra 25,0 ms (rangos disjuntos).
  A 3.500/s la diferencia es de la misma dirección pero cabe en el ruido (349 ± 19 contra 361 ± 18 us; p99 de 8,3 contra 15,7 ms).
- Mecanismo: con Serial se recolecta menos (119 pausas de 0,6 % del tiempo y máximo de 6,9 ms, contra 204 pausas de 1,3 % y máximo de 10,3 ms con G1), sin hilos de GC concurrentes ni barreras de escritura de G1, que en 2 núcleos compiten con los portadores.
- **No arregla la fase fría** (p99 de calentamiento de 431-557 ms con Serial, 318-1.463 ms con G1; sin diferencia clara).
- La imagen ya elegía Serial sola con menos de 2 núcleos o de 1.792 MB (por eso el `bench/docker-compose.pico.yml`, con `mem_limit: 1g`, ya lo usaba); el cambio lo hace explícito. En el `deploy/docker-compose.yml` (sin límites) elegía G1. Para una réplica de muchos núcleos o un
  heap grande, Serial deja de ser buena idea (un solo hilo de GC): el motor está dimensionado para ~2 núcleos y heap < 1 GB. Un `-XX:+UseG1GC` en `JAVA_TOOL_OPTIONS` choca con el `ENTRYPOINT` y la JVM no arranca; para cambiarlo hay que redefinir el `ENTRYPOINT`.
- Verificación: se construyó la imagen con el `Dockerfile` modificado y, con `--memory 3g` (donde la JVM habría elegido G1), `JAVA_TOOL_OPTIONS=-XX:+PrintFlagsFinal` muestra `UseSerialGC = true {command line}` y `UseG1GC = false`. La imagen de prueba se eliminó.

**Recomendado y NO implementado** (requiere código en el camino de la solicitud o en el arranque, o es de operación):

1. **Precalentar antes de aceptar tráfico** (código, arranque) — **implementado después y medido en [`warmup.md`](warmup.md)** (`SCHED_WARMUP`): al arrancar, y antes de que `/ready` responda 200, pasar un centenar de miles de solicitudes sintéticas por el camino completo (firma de un token de prueba con una llave efímera, validación, solver, JSON) hasta que el C2 haya
   compilado lo caliente (~30-40 s con 2 núcleos). Ganancia esperada, según los datos: la fase fría desaparece. Con la rampa de 120 s el p99 de calentamiento es de 7 ms y no hay descartadas, contra 1,3-1,5 s de p99 de calentamiento y 3.600-12.700 descartadas con la rampa de 20 s
   a 3.000-4.000/s. Hace falta no contaminar las métricas con ese tráfico y el costo es de unos 30-40 s más de arranque (hay que escalar antes de que abra la matrícula).
2. **Limitar las solicitudes en curso por réplica y responder 503 con `Retry-After`** (código, filtro) cuando la cola supere un umbral (p. ej. 64-128 solicitudes en vuelo, en el borde del planificador). No aumenta la capacidad, pero acota la latencia de lo aceptado: en la fase fría había
   100-316 tareas y ~290 solicitudes en los sockets (~80-250 ms de trabajo a 3.500/s) y la latencia llegaba a segundos porque nada la acota. Ganancia esperada: p99 de lo aceptado en decenas de ms durante el arranque, a cambio de rechazar (y reintentar) una parte de la
   fase fría. Traefik ya limita 100 solicitudes en vuelo por IP (`engine-inflight`), pero por IP de cliente, no por réplica.
3. **Mover la verificación de la firma a un pool acotado: no se recomienda.** La causa no es un bloque no cedible (el p99 sigue en ~19 ms a 4.500/s con 105 us de firma por solicitud) y no hay CPU libre que un pool extra pueda usar. Ganancia esperada: ninguna.
4. **Operación:** más núcleos o más réplicas antes de la apertura (con 4 núcleos, 3.500/s va al 36 % de CPU y sin fase fría); no dirigir el pico a una réplica recién arrancada (peso gradual en Traefik); dimensionar con ~2.500/s por réplica de 2 núcleos si puede arrancar fría con el pico encima,
   y con 4.000-4.500/s si está caliente.
5. **No probado:** AOT cache de JDK 25 (JEP 483/514/515) y CDS dinámico para acortar el arranque y la fase fría; requieren un paso de entrenamiento en el `Dockerfile` y el jar exploded de Spring Boot.

### Lo que no se explica y lo que no se verificó

- **Una lectura de `/actuator/prometheus` con la réplica cargada la frenó entre decenas de ms y ~2 s en 4 corridas** (la primera lectura de la ventana; en una corrida con lecturas cada 10 s, 2 de 7 lecturas coincidieron con tramos de p95 de 73-78 ms, con
  lecturas de 12-55 ms). No se encontró la causa. En producción Prometheus lee cada 15-30 s: conviene medirlo antes de dar por buena la capacidad con una réplica de 2 núcleos y revisar el costo de la agregación de `publishPercentiles` en los `Timer`.
- Un solo equipo (Ryzen 7 2700, `schedutil`, sin turbo), sin Traefik ni red real: las cifras absolutas no se trasladan a otra CPU, y el efecto de la frecuencia (apartado 6) es específico del governor.
- `taskset` de 2 hilos lógicos no es un `cpu_limit` de contenedor (no se midió con cgroup); el motor corrió como jar en el host con G1 por defecto (los experimentos con Serial son con el mismo jar).
- No se activó `tomcat.mbeanregistry` ni se midió el tiempo previo al filtro de la aplicación con un temporizador propio; la espera previa al filtro se infiere (histograma del motor contra k6, `submissions` del planificador y `Recv-Q` de los sockets).
- La fase fría solo se perfiló en una corrida (`ctimer`); no se identificaron los métodos que podrían evitar la compilación costosa, ni se probó el precalentamiento (propuesta 1; ver [`warmup.md`](warmup.md)) ni el límite de solicitudes en curso (propuesta 2).
- No se separó cuánto del factor 2 de la firma a baja utilización es frecuencia y cuánto estados de reposo y cachés.
- Se descartó `asprof -e wall`: la espera previa al filtro ocurre en el núcleo y en la cola del planificador (no en hilos que se puedan perfilar por espera), y el perfil de CPU de la fase fría respondía mejor a la pregunta.
- Son ~85 corridas con 1 a 3 repeticiones por configuración; el ruido entre corridas del mismo punto es grande (p99 de 6 a 22 ms a 3.500/s) y solo las diferencias con rangos disjuntos se tomaron como reales.

### Archivos de esta investigación

- `bench/overflow-run.sh` (una corrida con medición y parámetros), `bench/overflow-summary.py` (resumen), `bench/LatencyProbe.java` (sonda independiente), cambios en `bench/load-pico.js` (`WARM`, `SLICE_S`) y `bench/profile-server.sh` (`ENGINE_OPTS`).
- Ejemplo: `AUTH=jwt RATE=3500 WARM=30 MEAS=60 SLICE_S=10 WORK=/tmp/x bench/overflow-run.sh`; `ENGINE_OPTS=-XX:+UseSerialGC` para Serial; `ENGINE_CPUS=4,6,8,10 K6_CPUS=0-3,12-15` para 4 núcleos; `PROBE=1`, `POLL=1 POLL_FROM_START=1 POLL_S=1`, `COLD_PROF=1`, `JFR=1`, `STORM_RATE=0`.
