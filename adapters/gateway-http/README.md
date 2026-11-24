# adapters/gateway-http

**El adaptador de entrada HTTP: la API que usa el portal de matrícula del estudiante.** Traduce solicitudes
HTTP con JSON a llamadas a los casos de uso de `application` y los resultados, otra vez a JSON.

## Qué hay dentro

Paquete `sched.adapters.http` (`src/main/java/sched/adapters/http/`):

| Clases | Qué hacen |
|---|---|
| `CombinationsController`, `AlternativesController` | `POST /combinations` y `POST /alternatives` |
| `HealthController` | Las sondas `GET /health` (¿vive?) y `GET /ready` (¿puede recibir tráfico?, incluye el calentamiento) |
| `JwtAuthFilter`, `auth/JwtVerifier`, `auth/PublicKeys` | Verifican el token `Authorization: Bearer` (EdDSA/Ed25519, con BouncyCastle), eligen la llave por `kid` y entregan el contexto del estudiante ya verificado |
| `RequestIdFilter`, `BodySizeLimitFilter`, `AppSecurityHeadersFilter` | Id de correlación `X-Request-Id`, límite de tamaño del cuerpo (`413`) y cabeceras de seguridad del cliente web |
| `RequestValidation`, `RequestLimits`, `HttpExceptionHandler`, `HttpErrors` | Validación de la solicitud (`400`), límites por solicitud y traducción de errores a códigos HTTP |
| `src/main/resources/static/app/` | El **cliente web de demostración** (HTML, CSS y JavaScript sin framework), que se sirve en `/app/` sin token. Su lógica pura está en `js/logic.js` |

Las reglas de la API (campos, códigos de error, tiempo máximo) están en [api-http.md](../../docs/api-http.md).

## De qué depende y quién lo usa

- **Depende de:** `application`, Spring MVC, Jackson, la API de servlets y BouncyCastle (solo para verificar firmas).
- **Lo usa:** `server` (lo registra en Spring Boot) y `benchmarks` (mide la verificación de firmas con JMH).

## Compilar y probar solo este módulo

```sh
./mvnw -q -pl adapters/gateway-http -am test
./mvnw -q -pl adapters/gateway-http -Pjs-tests test     # además, la lógica del cliente con `node --test` (requiere Node 22.7 o superior; opcional)
```

## Herramienta de la comparación histórica con Rust

`ExportJwtVectorsTest` (en `src/test/.../auth/`) genera un archivo con más de 300 vectores de verificación de tokens y
la razón exacta de cada rechazo. Se escribió para comparar con la versión en Rust; hoy no se porta nada
a Rust y se conserva como herramienta de esa comparación y como cobertura extra del verificador. Siempre
escribe `adapters/gateway-http/target/jwt-vectors.json`; con `-Dsched.export.jwt-vectors=<ruta>` copia el archivo además a esa ruta:

```sh
./mvnw -q -pl adapters/gateway-http -am test -Dtest=ExportJwtVectorsTest -Dsurefire.failIfNoSpecifiedTests=false \
  -Dsched.export.jwt-vectors=/tmp/jwt-vectors.json
```

## Para entender más

[Spring MVC](../../docs/conceptos-base.md#spring-mvc) · [HTTP, JSON y REST](../../docs/conceptos-base.md#http-json-y-rest) ·
[JWT](../../docs/conceptos-base.md#jwt-y-bearer) · [BouncyCastle](../../docs/conceptos-base.md#bouncycastle) ·
[ADR 0004](../../docs/adr/0004-contexto-del-estudiante-en-token-firmado.md)
