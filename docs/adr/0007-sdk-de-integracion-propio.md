# 0007. Construir el SDK de integración como un agente propio

- **Estado:** Propuesto
- **Fecha:** 2026-10-14
- **Complementa:** [0002](0002-roles-de-la-institucion.md) (quién construye el cliente de la institución)

## Contexto

El [ADR 0002](0002-roles-de-la-institucion.md) dejó a cada universidad la tarea de generar su
cliente gRPC desde los `.proto` y de construir el componente que detecta cambios de cupo. Eso
tiene problemas:

- Cada universidad tendría que implementar bien reglas delicadas: numerar `seq` sin huecos,
  reaccionar a `ResyncRequired` y `Rejected`, mantener abierto el stream de cambios y
  reconectarse ([contrato-institucion.md](../contrato-institucion.md)). Un error ahí deja al
  motor desactualizado sin que nadie lo note.
- Es una barrera de entrada alta: el valor del motor depende de que integrarlo sea fácil.
- Los datos de matrícula viven casi siempre en una base de datos relacional del sistema
  académico, con esquemas distintos en cada universidad.

## Decisión

El proyecto **construye y mantiene el SDK**. Es un **agente** que cada universidad instala en
su infraestructura: lee su sistema académico, detecta los cambios y los envía al motor en
tiempo real. La universidad solo lo configura; no escribe código.

1. **Hexágono propio, separado del motor.** El SDK corre en otro lugar, con otro ciclo de vida.
   No es un adaptador del motor ni depende de sus módulos (`domain`, `application`…). Lo único
   que comparte con el motor es el contrato `proto/`.
2. **Mismo repositorio, carpeta `sdk/`.** Un cambio de contrato se hace en un solo commit para
   ambos lados, y la CI puede probar el SDK contra el motor real. Si más adelante necesita un
   ciclo de versiones propio, se separa sin costo, porque solo depende de `proto/`.
3. **Puertos del SDK:**
   - `FuenteAcademica` (salida): entrega la foto actual del catálogo de la universidad.
   - `Motor` (salida): carga completa y stream de cambios, con sus respuestas.
   - `EstadoLocal` (salida): último `seq` enviado y la foto anterior.
4. **Detección de cambios, v1:** consulta periódica (por ejemplo, cada 2 a 5 segundos) y
   comparación con la foto anterior. Funciona con cualquier base de datos sin tocarla. Métodos
   de menor latencia (CDC con Debezium, triggers, eventos del sistema académico) se agregan
   después como otros adaptadores de `FuenteAcademica`, sin cambiar el núcleo.
5. **Tiempo real hacia el motor:** el stream `SyncChanges` permanece abierto mientras el agente
   corre, y se reconecta con espera creciente si se cae.
6. **Configuración por universidad:** un archivo con la conexión a la base de datos y las
   consultas SQL que devuelven los grupos con columnas estándar.

Queda **abierto** y se decidirá antes de empezar la implementación:

- **Lenguaje.** Como es un agente que habla con la base de datos y no una librería que se
  incrusta en el código de la universidad, el lenguaje de la universidad no importa. Se
  recomienda **Java**: es el que conoce el equipo, comparte las clases generadas de `proto/` y
  JDBC tiene drivers para todas las bases de datos comunes.
- **Distribución:** imagen de Docker, jar ejecutable o ambos.
- **Seguridad del canal:** TLS y autenticación del agente ante el motor.
- **Firma del token del estudiante** ([ADR 0004](0004-contexto-del-estudiante-en-token-firmado.md)):
  si el SDK incluye además una pequeña librería o un ejemplo para emitirlo.

El diseño detallado está en [sdk.md](../sdk.md).

## Consecuencias

- Integrar el motor pasa de "implementar un protocolo" a "configurar un agente".
- Las reglas de sincronización se implementan y prueban una sola vez.
- El proyecto asume el mantenimiento del agente y el soporte de varios motores de base de
  datos.
- La consulta periódica añade unos segundos de latencia y carga de lectura sobre la base de la
  universidad. Debe poder ajustarse y, si hace falta, apuntar a una réplica de lectura.
- El ADR 0002 sigue vigente (la institución empuja y el motor nunca consulta). Solo cambia quién
  construye el componente que empuja.

## Alternativas consideradas

- **Que cada universidad genere su cliente (ADR 0002 original):** barrera alta y reglas de
  sincronización reimplementadas, y probablemente con errores, en cada universidad.
- **Librerías cliente en varios lenguajes:** hay que mantener una por lenguaje y la universidad
  igual debe escribir la detección de cambios.
- **El motor lee directamente la base de la universidad:** contradice el ADR 0002, acopla el
  motor a cada esquema y exige abrir la base de datos hacia fuera.
- **Repositorio separado desde el inicio:** obliga a versionar y publicar el contrato antes de
  que esté estable.
