# Glosario

Términos del proyecto. Entre paréntesis, el nombre que se usa en el código cuando es distinto.

| Término | Significado |
|---|---|
| **Catálogo** (`Catalog`) | Todos los grupos ofertados en el periodo, con sus horarios y cupos. Es de la institución; el motor solo guarda una réplica |
| **Curso** (`CourseId`, `course_id`) | Asignatura, por ejemplo `MAT101`. Un estudiante elige un grupo de cada curso |
| **Grupo** (`Section`, `section_id`) | Una oferta concreta de un curso, con horario y cupo propios, por ejemplo `MAT101-01`. En el código y en el contrato se llama *section* |
| **Franja** (`TimeSlot`, `slot`) | Bloque semanal de clase: día, minuto de inicio y minuto de fin. Es `[inicio, fin)`, así que una clase que termina a las 08:00 no se cruza con otra que empieza a las 08:00 |
| **Cupo** (`capacity`, `available_seats`) | `capacity` es el cupo total; `available_seats` es lo que queda libre. Un grupo sin cupo libre no se ofrece |
| **Cruce** (`overlaps`) | Dos franjas del mismo día que se superponen. Una combinación válida no tiene cruces |
| **Combinación** (`Schedule`) | Un grupo por cada curso pedido, sin cruces entre ellos |
| **Contexto del estudiante** (`StudentContext`) | Lo que el motor sabe del estudiante en una solicitud: su identificador y sus cursos habilitados. Llegará en un token firmado ([ADR 0004](adr/0004-contexto-del-estudiante-en-token-firmado.md)) |
| **Cursos habilitados** (`eligible_courses`) | Cursos que el estudiante puede matricular según la institución (prerrequisitos, plan, etc.) |
| **Solver** | El algoritmo que busca combinaciones. Hoy es `BacktrackingSolver`: búsqueda exhaustiva que descarta ramas con cruces |
| **`seq`** | Número de secuencia que asigna la institución a cada carga y a cada cambio. Crece de uno en uno y permite detectar eventos perdidos |
| **Carga completa** (`LoadCatalog`, `Snapshot`) | Envío del catálogo entero. Reemplaza la réplica y fija el `seq` de partida |
| **Cambio** o **evento** (`CatalogEvent`) | Modificación incremental: `seats_changed`, `section_upserted` o `section_removed` |
| **Hueco** (`SequenceGap`) | Llegó un `seq` mayor que el siguiente esperado: se perdió al menos un cambio |
| **Resync** (`ResyncRequired`) | Respuesta del motor que pide una carga completa, tras un hueco o si aún no tiene catálogo |
| **Réplica** (`CatalogReplica`) | Copia en memoria del catálogo que tiene cada instancia del motor |
| **Desactualizada** (`stale`) | La réplica podría no reflejar lo último de la institución, por un hueco o porque se cerró la conexión de cambios. El motor sigue respondiendo, pero lo indica |
| **Historial** (`CatalogLog`) | Registro ordenado en NATS JetStream con la última carga completa y los cambios posteriores. Todas las instancias lo leen para armar su réplica ([ADR 0005](adr/0005-varias-instancias-con-historial-compartido.md)) |
| **`SyncLost`** | Entrada del historial que indica que la ingesta perdió la sincronía; hace que todas las réplicas pasen a `stale` |
| **Ingesta** (rol `ingest`) | La única instancia que recibe datos de la institución por gRPC y escribe el historial |
| **Consulta** (rol `query`) | Instancias que atienden a los estudiantes por HTTP. Se escalan horizontalmente |
| **Rol** (`SCHED_ROLE`) | `ingest`, `query` o `all` (ambos en un proceso) |
| **Lista** (`Readiness`, `/ready`) | La instancia ya leyó todo el historial y su réplica está al día; el balanceador puede enviarle tráfico |
| **Institución** | La universidad: dueña de los datos y fuente de verdad. El motor nunca la consulta ([ADR 0002](adr/0002-roles-de-la-institucion.md)) |
| **SDK** o **agente** | Programa que instalará cada universidad para enviar su catálogo al motor ([diseño](sdk.md)). Todavía no existe |
| **Institución simulada** (`FakeInstitution`) | Cliente gRPC del simulador que hace el papel del SDK en desarrollo ([simulador.md](simulador.md)) |
| **Puerto** / **adaptador** | Términos de la arquitectura hexagonal: un puerto es una interfaz del núcleo y un adaptador la implementa con una tecnología concreta ([ADR 0001](adr/0001-arquitectura-hexagonal.md)) |
| **ADR** | *Architecture Decision Record*: documento corto que registra una decisión de arquitectura y su porqué ([docs/adr/](adr/)) |
