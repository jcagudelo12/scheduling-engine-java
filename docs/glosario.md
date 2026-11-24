# Glosario

Términos del proyecto, agrupados por tema. Entre paréntesis, el nombre que se usa en el código o en
el contrato cuando es distinto. Las tecnologías se explican desde cero, con ejemplos, en
[conceptos-base.md](conceptos-base.md); aquí solo hay una definición corta y el enlace.

- [Dominio](#dominio): lo que el motor sabe de la universidad y del estudiante
- [Algoritmo](#algoritmo): cómo se buscan las combinaciones
- [Operación](#operación): cómo se mantiene el catálogo, las instancias y la salud del sistema
- [Tecnologías](#tecnologías)
- [Simulación y estadística](#simulación-y-estadística)

## Dominio

| Término | Significado |
|---|---|
| **Institución** | La universidad: dueña de los datos y fuente de verdad. El motor nunca la consulta ([ADR 0002](adr/0002-roles-de-la-institucion.md)) |
| **Catálogo** (`Catalog`) | Todos los grupos ofertados en el periodo, con sus horarios y cupos. Es de la institución; el motor solo guarda una réplica |
| **Curso** (`CourseId`, `course_id`) | Asignatura, por ejemplo `MAT101`. Un estudiante elige un grupo de cada curso |
| **Grupo** (`Section`, `section_id`) | Una oferta concreta de un curso, con horario y cupo propios, por ejemplo `MAT101-01`. En el código y en el contrato se llama *section* |
| **Franja** (`TimeSlot`, `slot`) | Bloque semanal de clase: día, minuto de inicio y minuto de fin. Es `[inicio, fin)`, así que una clase que termina a las 08:00 no se cruza con otra que empieza a las 08:00 |
| **Cupo** (`capacity`, `available_seats`) | `capacity` es el cupo total; `available_seats` es lo que queda libre. Un grupo sin cupo libre no se ofrece |
| **Créditos** (`credits`) | Valor de una asignatura, igual para todos sus grupos. Lo informa la institución en la carga completa; un curso sin créditos informados cuenta 0 |
| **Tope de créditos** (`max_credits`, `EnrollmentRules`) | Máximo de créditos que un estudiante puede matricular en el periodo, parametrizado por la institución. 0 = sin tope. Se verifica antes de la búsqueda; si se supera, la API responde `422` |
| **Estudiante** (`student_id`, `StudentId`) | Identificador del estudiante que consulta. Sale del campo `sub` del token firmado. El motor no guarda estudiantes: el identificador vive solo durante la solicitud y nunca se escribe en los logs |
| **Contexto del estudiante** (`StudentContext`) | Lo que el motor sabe del estudiante en una solicitud: su identificador (`student_id`), sus cursos habilitados y sus preferencias. Llega en un token firmado ([ADR 0004](adr/0004-contexto-del-estudiante-en-token-firmado.md)) |
| **Cursos habilitados** (`eligible_courses`) | Cursos que el estudiante puede matricular según la institución (prerrequisitos, plan, etc.). Salen del campo `courses` del token |
| **Cruce** (`overlaps`) | Dos franjas del mismo día que se superponen. Una combinación válida no tiene cruces |
| **Combinación** (`Schedule`) | Un grupo por cada curso pedido, sin cruces entre ellos y con cupo. Es lo que devuelve `POST /combinations` |
| **Selección** (`selection`) | En `POST /alternatives`: lo que el estudiante tiene elegido hoy, `{ "<curso>": "<grupo>" }`. Puede no cubrir todos los cursos. Es el punto de partida para buscar alternativas que cambien lo mínimo |
| **Inscritos** (`enrolled`) | En `POST /alternatives`: los grupos de la `selection` que el estudiante **ya matriculó**. Cuentan como con cupo aunque la réplica marque 0 (quizá el último cupo lo tomó él mismo), no generan `no_seats` y pueden conservarse sin costo |
| **Restricción suave** (`Preferences`, `avoid`) | Preferencia del estudiante que no descarta combinaciones, solo las ordena. Hoy: ventanas de horario a evitar |
| **Evitar** (`avoid`) | Campo de la solicitud con las ventanas de tiempo (día, minuto de inicio y fin) que el estudiante prefiere no tener ocupadas. Una combinación que cae en ellas no se descarta: solo cuesta más |
| **Penalización** (`penalty`) | Costo de una combinación según las preferencias: número de franjas elegidas que se solapan con alguna ventana de `avoid`. Se devuelve primero la de menor penalización |
| **Distancia** (`distance`) | De una alternativa `a` a la selección original `s`: d(a, s) = \|{ i : aᵢ ≠ sᵢ }\|, el número de cursos con elección cuyo grupo cambió. Un curso sin elección no suma |
| **Alternativa** (`Alternative`, `POST /alternatives`) | Combinación sin cruces y con cupo que sustituye una selección que dejó de servir (grupo sin cupo o cruce), cambiando lo mínimo. Cada cambio indica su razón: `no_seats`, `clash`, `not_selected` o `rearranged` |
| **Orden lexicográfico** | Orden de las alternativas: primero menor distancia y, solo a igual distancia, menor penalización. No es una suma ponderada: conservar los grupos elegidos tiene prioridad absoluta sobre las preferencias |
| **Completa** (`complete`) | Campo de la respuesta. `true`: la búsqueda terminó y el resultado es exacto. `false`: venció el tiempo máximo de búsqueda; las combinaciones son válidas, pero puede haber mejores o más ([ADR 0009](adr/0009-tiempo-maximo-de-la-busqueda.md)) |
| **Oferta sintética** | Catálogo inventado por el simulador, con la forma de una oferta real del ITM (programas, semestres, grupos populares) para probar sin datos reales. Se genera con `demo-catalog` o dentro del experimento ([simulador.md](simulador.md#oferta-sintética-estilo-itm)) |

## Algoritmo

| Término | Significado |
|---|---|
| **Solver** | El algoritmo que busca combinaciones. Hay dos: **`ForwardCheckingSolver`** (`SCHED_SOLVER=forward-checking`), que es el **valor por defecto** del motor, y **`BacktrackingSolver`** (`SCHED_SOLVER=backtracking`), la línea base para comparar. Ambos dan el mismo resultado. El comando `simulate` del simulador usa siempre el segundo |
| **CSP** | *Constraint Satisfaction Problem*: variables, dominios y restricciones. Armar un horario es un CSP ([conceptos](conceptos-base.md#csp-problema-de-satisfacción-de-restricciones)) |
| **Backtracking** | Búsqueda con retroceso: probar una opción y volver atrás si choca ([conceptos](conceptos-base.md#backtracking)) |
| **Forward checking** | Al elegir un grupo, quitar de inmediato de los demás cursos los grupos que chocan con él; si un curso se queda sin opciones, retroceder ([conceptos](conceptos-base.md#forward-checking)) |
| **MRV** | *Minimum Remaining Values*: decidir primero el curso con menos grupos posibles ([conceptos](conceptos-base.md#mrv)) |
| **Ramificación y poda** | Buscar las *k* mejores combinaciones por costo descartando ramas cuya cota ya no puede mejorar a la k-ésima ([conceptos](conceptos-base.md#ramificación-y-poda), [ADR 0006](adr/0006-mejores-combinaciones-con-ramificacion-y-poda.md)) |
| **Costo** (`SectionCost`) | Número no negativo que el solver suma por cada grupo elegido; permite ordenar. La penalización de `avoid` y la distancia de las alternativas se codifican como costo |
| **Tiempo máximo de búsqueda** (`SCHED_SEARCH_TIMEOUT_MS`) | Plazo de cada búsqueda (150 ms por defecto). Al vencer se responde con lo encontrado y `complete: false` |
| **Combinaciones máximas** (`SCHED_MAX_COMBINATIONS`) | Cuántas combinaciones devuelve como máximo `POST /combinations`: 200 por defecto. Las pruebas de carga lo fijan en 5 (lo que vería un estudiante) |
| **Alternativas máximas** (`SCHED_MAX_ALTERNATIVES`) | Cuántas alternativas devuelve `POST /alternatives`: 5 por defecto |

## Operación

| Término | Significado |
|---|---|
| **`seq`** | Número de secuencia que asigna la institución a cada carga y a cada cambio. Crece de uno en uno y permite detectar eventos perdidos |
| **Carga completa** (`LoadCatalog`, `Snapshot`) | Envío del catálogo entero. Reemplaza la réplica y fija el `seq` de partida |
| **Cambio** o **evento** (`CatalogEvent`) | Modificación incremental: `seats_changed`, `section_upserted` o `section_removed` |
| **Hueco** (`SequenceGap`) | Llegó un `seq` mayor que el siguiente esperado (por ejemplo, 5 cuando se esperaba 3): se perdió al menos un cambio |
| **Ack** | Respuesta de la ingesta: el cambio con ese `seq` quedó en el historial. Reenviar un `seq` ya aceptado también responde `Ack` y no cambia nada |
| **Resync** (`ResyncRequired`) | Respuesta que pide una carga completa, tras un hueco de `seq` o si aún no hay catálogo. Trae el `seq` esperado y el recibido |
| **Rejected** | Respuesta que dice que el cambio **no** se aplicó: datos inválidos (por ejemplo, una franja que termina antes de empezar) o fallo al escribir en el historial. El `seq` no se consume: se corrige y se reenvía el mismo |
| **Réplica** (`CatalogReplica`) | Copia en memoria del catálogo que tiene cada instancia del motor |
| **Desactualizada** (`stale`) | La réplica podría no reflejar lo último de la institución, por un hueco o porque se cerró la conexión de cambios. El motor sigue respondiendo, pero lo indica (`"stale": true`) y deja de estar lista |
| **Historial** (`CatalogLog`) | Registro ordenado en NATS JetStream con la última carga completa y los cambios posteriores. Todas las instancias lo leen para armar su réplica ([ADR 0005](adr/0005-varias-instancias-con-historial-compartido.md)) |
| **`SyncLost`** | Entrada del historial que indica que la ingesta perdió la sincronía; hace que todas las réplicas pasen a `stale` |
| **Follower** (`CatalogFollower`) | El hilo de cada instancia que lee el historial de NATS y aplica cada entrada a su réplica. La métrica `sched_follower_lag` dice cuántos mensajes le faltan por leer |
| **Ingesta** (rol `ingest`) | La única instancia que recibe datos de la institución por gRPC y escribe el historial |
| **Consulta** (rol `query`) | Instancias que atienden a los estudiantes por HTTP. Se escalan horizontalmente |
| **Rol** (`SCHED_ROLE`) | `ingest`, `query` o `all` (ambos en un proceso) |
| **Lista** (`Readiness`, `/ready`) | Que la instancia **puede recibir tráfico**: ya leyó todo el historial, su réplica tiene catálogo y no está `stale` (se recalcula en cada consulta) y, en los roles que atienden consultas, **ya terminó el calentamiento de la JVM** (una sola vez por proceso). Mientras no lo esté, `/ready` responde `503` y el balanceador no le envía solicitudes |
| **Calentamiento** (*warmup*, `SCHED_WARMUP`) | Antes de decir que está lista, la instancia ejecuta consultas sintéticas en proceso para que el compilador JIT optimice el código (10 a 60 s). Evita que las primeras solicitudes reales vayan lentas ([inicio rápido](inicio-rapido.md#calentamiento-de-la-jvm)) |
| **Sonda** (*probe*) | Una petición periódica que un programa externo hace para saber si el motor funciona. Hay dos: `/health` (¿está vivo?, no depende del catálogo ni del calentamiento) y `/ready` (¿puede recibir tráfico?) ([API](api-http.md#sondas)). En las pruebas de carga, "sonda" también es una solicitud extra que mide la latencia por separado (`LatencyProbe`) |
| **Puerto de administración** (`SCHED_MANAGEMENT_PORT`, 8081) | Puerto aparte del de los estudiantes (8080) donde se sirven las métricas y el estado interno, **sin token**. Solo debe alcanzarse desde la red interna |
| **`X-Request-Id`** | Cabecera HTTP con un identificador de la solicitud. El motor la acepta (si es segura) o genera una, la devuelve en la respuesta y la escribe en cada línea de log de esa solicitud (`request_id`) para seguir una consulta con problemas |
| **SDK** o **agente** | Programa que instalará cada universidad para enviar su catálogo al motor ([diseño](sdk.md)). Todavía no existe |
| **Institución simulada** (`FakeInstitution`) | Cliente gRPC del simulador que hace el papel del SDK en desarrollo ([simulador.md](simulador.md)) |
| **Ráfaga de cupos** (`seat-storm`) | Comando del simulador que vacía muchos grupos a ritmo fijo para reproducir el instante en que se llenan los grupos populares al abrir la matrícula; mide cuánto tarda el motor en dejar de ofrecerlos (escenario E1) |
| **E1 a E5** | Los cinco escenarios de calidad del proyecto (un grupo se llena, una búsqueda explota, el SDK detecta cambios, efecto manada, el motor no responde a tiempo) ([04-escenarios.md](calidad/04-escenarios.md)) |
| **Puerto** / **adaptador** | Términos de la arquitectura hexagonal: un puerto es una interfaz del núcleo y un adaptador la implementa con una tecnología concreta ([ADR 0001](adr/0001-arquitectura-hexagonal.md), [conceptos](conceptos-base.md#arquitectura-hexagonal-puertos-y-adaptadores)) |
| **ADR** | *Architecture Decision Record*: documento corto que registra una decisión de arquitectura y su porqué ([docs/adr/](adr/), [conceptos](conceptos-base.md#adr-decisiones-de-arquitectura)) |

## Tecnologías

Cada término se explica con calma en [conceptos-base.md](conceptos-base.md).

| Término | Significado corto |
|---|---|
| [**JDK / JVM**](conceptos-base.md#java-jdk-y-jvm) | El kit para compilar Java y la máquina virtual que lo ejecuta. El proyecto exige la versión 25 |
| [**JIT**](conceptos-base.md#jit-y-calentamiento) | Compilador de la JVM que optimiza el código mientras corre; por eso el arranque en frío es lento |
| [**Recolector de basura** (GC)](conceptos-base.md#recolector-de-basura) | Libera la memoria que ya nadie usa. La imagen usa el recolector Serial |
| [**Maven** / `./mvnw`](conceptos-base.md#maven-y-el-wrapper-mvnw) | Herramienta que compila, prueba y empaqueta; el *wrapper* descarga la versión correcta |
| [**Jar ejecutable**](conceptos-base.md#jar-ejecutable) | Jar con todo incluido: `sched-server-exec.jar` se ejecuta; `sched-server.jar` no |
| [**Spring Boot**](conceptos-base.md#spring-boot) / [**Spring MVC**](conceptos-base.md#spring-mvc) | Marco para el servidor HTTP y la configuración |
| [**Tomcat**](conceptos-base.md#tomcat) | El servidor web embebido que escucha en el 8080 |
| [**Hilos virtuales**](conceptos-base.md#hilos-virtuales) | Hilos muy livianos de Java: uno por solicitud |
| [**Actuator**](conceptos-base.md#actuator) | Módulo que publica estado y métricas en el puerto de administración |
| [**HTTP / JSON / REST**](conceptos-base.md#http-json-y-rest), [**Jackson**](conceptos-base.md#jackson) | El protocolo y formato de la API del estudiante, y la librería que los convierte a objetos Java |
| [**gRPC** / **Protocol Buffers**](conceptos-base.md#grpc-y-protocol-buffers) | Llamadas remotas y formato binario del contrato con la institución (`.proto`) |
| [**TLS / mTLS**](conceptos-base.md#tls-mtls-certificados-ca-y-openssl) | Cifrado de la conexión; en mTLS el servidor también exige certificado al cliente |
| [**Certificado / CA**](conceptos-base.md#tls-mtls-certificados-ca-y-openssl) | La "cédula digital" de un servidor o cliente y la autoridad que la firma |
| [**OpenSSL**](conceptos-base.md#tls-mtls-certificados-ca-y-openssl) | Programa de línea de comandos para crear y revisar llaves y certificados |
| [**JWT** / **Bearer**](conceptos-base.md#jwt-y-bearer) | Token firmado con `cabecera.contenido.firma` que viaja en `Authorization: Bearer …` |
| [**EdDSA / Ed25519**](conceptos-base.md#firma-eddsa-ed25519-y-llaves-pública-y-privada) | Algoritmo de firma digital con par de llaves pública y privada |
| **Llave pública / privada** | La privada firma (la guarda la institución); la pública solo verifica (la tiene el motor) |
| [**`kid`**](conceptos-base.md#kid-el-identificador-de-la-llave) | Nombre de la llave con que se firmó un token; permite rotar llaves |
| [**BouncyCastle**](conceptos-base.md#bouncycastle) | Librería de criptografía que verifica las firmas Ed25519 |
| [**NATS / JetStream**](conceptos-base.md#nats-y-jetstream) | Mensajería con persistencia; guarda el historial del catálogo. Un **stream** es la secuencia ordenada de mensajes y un **consumidor** quien la lee |
| [**Docker**](conceptos-base.md#docker) | Empaqueta el programa en una **imagen** y lo ejecuta como **contenedor**; un **volumen** conserva datos y una **red** conecta contenedores |
| [**Docker Compose**](conceptos-base.md#docker-compose) | Describe y levanta varios contenedores juntos; un **perfil** activa servicios opcionales |
| [**Healthcheck**](conceptos-base.md#healthcheck) | Comprobación periódica de que el contenedor funciona |
| [**Traefik**](conceptos-base.md#traefik) | Balanceador de carga con límites por IP, de solicitudes simultáneas y de tamaño |
| [**Kubernetes**](conceptos-base.md#kubernetes-solo-como-guía) | Orquestador de contenedores (solo guía): *pod*, sondas de preparación/vida/arranque y *rolling update* |
| [**Prometheus / Micrometer**](conceptos-base.md#prometheus-micrometer-y-métricas) | Micrometer registra métricas en el código y Prometheus las recoge y consulta |
| [**p99**](conceptos-base.md#prometheus-micrometer-y-métricas) | Percentil 99 de la latencia: el 99 % de las solicitudes tardó menos que este valor |
| [**Logback / ECS**](conceptos-base.md#logs-y-formato-ecs) | Librería de logs y estándar de campos para logs en JSON (`SCHED_LOG_FORMAT=json`) |
| [**JUnit 5**](conceptos-base.md#junit-5) | Marco de pruebas de Java |
| [**jqwik**](conceptos-base.md#pruebas-basadas-en-propiedades-jqwik) | Pruebas basadas en propiedades: una regla probada con muchas entradas aleatorias |
| [**Testcontainers**](conceptos-base.md#testcontainers) | Levanta contenedores reales (NATS) desde las pruebas |
| [**ArchUnit**](conceptos-base.md#archunit) | Reglas de arquitectura escritas como pruebas |
| [**JaCoCo** / cobertura](conceptos-base.md#jacoco-y-la-cobertura) | Mide qué porcentaje del código ejecutan las pruebas |
| [**JMH**](conceptos-base.md#jmh) | Microbenchmarks de funciones pequeñas |
| [**k6**](conceptos-base.md#k6) | Generador de carga HTTP |
| [**async-profiler / JFR**](conceptos-base.md#async-profiler-y-jfr) | Perfiladores: dicen en qué métodos gasta el tiempo el programa (*flamegraph*) |
| [**GitHub Actions**](conceptos-base.md#github-actions-integración-continua) | Integración continua: compila y prueba cada cambio automáticamente |
| [**Mermaid**](conceptos-base.md#mermaid) | Diagramas escritos como texto dentro del Markdown |
| [**Conventional Commits**](conceptos-base.md#conventional-commits) | Convención para los mensajes de commit (`feat(scope): resumen`) |
| [**YAML**, **JDBC**, **PostgreSQL**, **CDC**](conceptos-base.md#piezas-del-sdk-propuesto-sql-jdbc-postgresql-yaml-y-cdc) | Piezas del SDK propuesto (todavía no existe) |

## Simulación y estadística

El [simulador](simulador.md) compara la matrícula actual con la asistida por el motor. Los conceptos
estadísticos están explicados a nivel intuitivo en
[conceptos-base.md](conceptos-base.md#estadística-del-simulador-a-nivel-intuitivo).

| Término | Significado |
|---|---|
| **Escenario** | Dos cosas distintas según el contexto. (1) En `simulate`: el archivo JSON con un catálogo y unos estudiantes ([formato](simulador.md#formato-del-escenario)). (2) En la carpeta de calidad: uno de los cinco escenarios de calidad E1 a E5 |
| **Perfil** | Un estudiante sintético del experimento: programa, semestre, asignaturas, jornada preferida, rasgos de comportamiento y ventana de matrícula. Cada perfil matricula en las dos condiciones (A y B) |
| **Condición reactiva (A)** | La matrícula de hoy, por ensayo y error: el estudiante elige un grupo y solo al enviarlo se entera de si choca o no tiene cupo |
| **Condición asistida (B)** | El estudiante pide al motor las combinaciones válidas y elige una; si al enviarla alguien le quitó un cupo, pide alternativas |
| **Semilla** (`--seed`) | Número que fija el azar: la misma semilla produce los mismos resultados |
| **Prueba de hipótesis**, **H0**, **alfa**, **valor p** | Método para decidir si una diferencia es real o casualidad; H0 es la afirmación que se intenta rechazar (aquí, "la reducción es a lo sumo 20 %") y alfa el riesgo de error aceptado (0,05) |
| **Shapiro-Wilk** | Prueba que dice si los datos parecen normales; decide si se usa la t o Wilcoxon |
| **Prueba t** / **Wilcoxon** | Pruebas para comparar contra un valor: la t supone normalidad, Wilcoxon no |
| **Bootstrap** | Remuestreo de los propios datos para estimar un intervalo de confianza sin suponer una distribución |
| **Tamaño del efecto** | Qué tan grande es una diferencia, independiente del número de datos |
| **Potencia** | Probabilidad de detectar una diferencia real; define cuántos perfiles se necesitan |
| **Calibración** | Ajustar los parámetros del flujo A para que reproduzcan la consulta real |
| **Sensibilidad** | Cambiar los supuestos para ver cuánto resiste la conclusión |
| **`Ttotal`** (`t_total_s`) | Tiempo total, en segundos simulados, que tarda un perfil en terminar de matricular |
| **Rtraslape** (`r_traslape`) | Cuántas veces la plataforma simulada rechazó un envío por cruce de horario. En B debe ser 0 |
| **Rcupo** (`r_cupo`) | Rechazos por falta de cupo (otro estudiante tomó el último) |
| **Rtotal** (`r_total`) | Rtraslape + Rcupo |
| **Reducción** (`r`) | (Ttotal de A − Ttotal de B) / Ttotal de A: la fracción de tiempo que se ahorra con el motor. La hipótesis principal compara su media con 0,20 |
| **Plataforma simulada** | La "fuente de verdad" del experimento: valida los cruces y los cupos de cada envío sin usar el solver |
