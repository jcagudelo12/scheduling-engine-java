# 0004. Recibir el contexto del estudiante en un token firmado por la institución

- **Estado:** Aceptado
- **Fecha:** 2026-09-25

## Contexto

Además del catálogo, el motor necesita datos propios de cada estudiante: cursos
habilitados, grupos ya inscritos y tope de créditos. La institución ya los conoce al
cargar la vista del estudiante. Si esos datos viajaran sin protección desde el navegador,
el estudiante podría alterarlos (por ejemplo, agregarse un curso sin prerrequisitos).

## Decisión

Al cargar la vista, la institución emite un **token firmado** (JWT con EdDSA/Ed25519) con:

- `sub`: identificador del estudiante.
- Cursos habilitados, grupos inscritos y tope de créditos.
- `exp`: vencimiento corto (la duración de la sesión de matrícula).

El navegador envía el token en cada solicitud al gateway y al abrir el WebSocket. El
adaptador de entrada verifica la firma con la **llave pública** de la institución y
entrega a los casos de uso un `StudentContext` ya verificado.

## Consecuencias

- El motor sigue sin llamadas de salida y sin estado por estudiante.
- El mismo token autentica al estudiante en el WebSocket.
- La institución solo necesita firmar un JWT, algo estándar en cualquier stack; el SDK
  incluirá un ejemplo.
- Si la situación del estudiante cambia durante la sesión, el cambio se refleja cuando la
  institución emita un token nuevo.
- La verificación vive en los adaptadores de entrada; los casos de uso reciben el
  contexto y no saben de tokens.

## Alternativas consideradas

- **Contexto sin firmar desde el navegador:** el estudiante puede manipularlo.
- **El backend de la institución como intermediario:** cada solicitud pasa por sus
  servidores y el WebSocket queda partido entre dos sistemas.
- **Consulta del motor a la institución por estudiante:** contradice el ADR 0002.

## Nota de implementación (2026-11-22)

Implementado en el adaptador HTTP (`adapters/gateway-http`: `JwtAuthFilter` y el paquete
`auth`), solo con el JDK (`Signature` "Ed25519") y Jackson, sin librerías JWT.

- **Verificación:** JWS compacto con `alg` exactamente `EdDSA` (se rechazan `none`, HS256, RS256
  y cualquier otro; también `crit`, `jku` y `jwk`). La firma se verifica **antes** de leer el
  payload. La llave se elige por `kid` entre varias públicas (`SCHED_JWT_PUBLIC_KEYS`), lo que
  permite rotar; sin `kid` solo se acepta si hay una única llave. Se validan `exp` e `iat`
  (obligatorios), `nbf` (opcional), `iss` y `aud` configurados, con una tolerancia de reloj
  (`SCHED_JWT_CLOCK_SKEW_S`, 30 s) y un tope de vida `exp - iat` (`SCHED_JWT_MAX_TTL_S`, 900 s).
  Un token de más de 8 KB se rechaza sin decodificarlo.
- **Claims:** `sub` es el estudiante y `courses` sus cursos habilitados; el contexto verificado
  llega a los controladores como atributo de la solicitud. Con token, `student_id` y
  `eligible_courses` del cuerpo son opcionales y, si vienen y difieren del token, la respuesta es
  `400`.
- **Alcance respecto de la decisión original:** los grupos inscritos (`enrolled`) y las
  preferencias siguen en el cuerpo, porque son elecciones del estudiante y no una verdad que la
  institución deba afirmar; el tope de créditos sale de las reglas del periodo que la
  institución carga en el catálogo, no del token.
- **Errores:** sin token o con uno inválido, `401 {"error":"unauthorized"}` con
  `WWW-Authenticate: Bearer`, igual para todo motivo. El log guarda solo el motivo genérico y el
  `kid`, nunca el token ni su contenido. `/health` y `/ready` no lo exigen.
- **Configuración:** `SCHED_AUTH=jwt` (por defecto) o `none` (solo desarrollo, con advertencia).
  Con `jwt` y sin llaves, emisor o audiencia, el servidor no arranca.
- **Pendiente:** el WebSocket de avisos en vivo (cuando exista) debe verificar el mismo token al
  abrirse. Las herramientas `keygen` y `token` del simulador firman tokens de prueba.
