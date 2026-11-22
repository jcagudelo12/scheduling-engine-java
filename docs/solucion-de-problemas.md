# Solución de problemas

Errores comunes al correr el motor y cómo resolverlos. Si encuentras uno que no está aquí,
agrégalo.

## Compilación

### `release version 25 not supported` o `UnsupportedClassVersionError`

Estás usando un JDK anterior a 25. El proyecto compila para Java 25 y los jars no corren en
versiones anteriores.

```sh
java -version     # debe decir 25
```

Instala un JDK 25 (por ejemplo Temurin) y asegúrate de que `JAVA_HOME` y `PATH` apunten a él.
Si no quieres instalarlo, usa el camino con Docker de [inicio-rapido.md](inicio-rapido.md#b-sistema-completo-con-docker).

### `./mvnw: Permission denied`

```sh
chmod +x mvnw
```

En Windows usa `mvnw.cmd`.

## Arranque del motor

### `Unable to connect to NATS servers: [nats://localhost:4222]`

El motor siempre necesita NATS, incluso con `SCHED_ROLE=all`. Levántalo:

```sh
docker run -d --rm --name sched-nats -p 127.0.0.1:4222:4222 nats:2 -js
```

Si NATS está en otra dirección, indícala con `SCHED_NATS_URL`. El `-js` es obligatorio: sin
JetStream no hay historial.

### `Unable to start embedded Tomcat server` (puerto 8080 ocupado)

Otro proceso usa el puerto 8080, que puede ser el despliegue de Docker (Traefik) si lo dejaste
corriendo. Opciones:

```sh
docker compose -f deploy/docker-compose.yml down   # si es el despliegue
SCHED_HTTP_PORT=8081 java -jar server/target/sched-server.jar
```

Para ver qué usa el puerto: `ss -ltnp | grep 8080` en Linux, `lsof -i :8080` en macOS.

### `no se pudo abrir el puerto gRPC 50051`

Igual que el anterior, pero con el puerto de ingesta. Cambia `SCHED_GRPC_PORT` o detén el otro
proceso. Ojo: este error **no detiene** el motor; el HTTP sigue arriba pero no podrás cargar
datos.

### `SCHED_ROLE inválido: …`

Los únicos valores válidos son `ingest`, `query` y `all`.

## Datos y consultas

### `/combinations` responde `503 el catálogo aún no se ha cargado`

El motor no ha recibido ninguna carga completa. Carga el catálogo:

```sh
java -jar simulation/target/sched-simulation.jar load
# o con Docker:
docker compose -f deploy/docker-compose.yml run --rm simulator load
```

`/ready` responde `503` mientras no haya catálogo: ver [api-http.md](api-http.md#sondas).

### El simulador dice `error: la ingesta exige TLS con autenticación mutua`

La ingesta exige mTLS por defecto. Pasa `--tls-cert`, `--tls-key` y `--tls-ca` (o sus variables
`SCHED_INGEST_TLS_*`); con `deploy/setup-dev.sh` ya están en `deploy/certs/client`. Contra un
motor con `SCHED_GRPC_TLS=disabled` usa `--plaintext`. Ver [simulador.md](simulador.md).

### El simulador dice `error: UNAVAILABLE: ssl exception` o `Network closed for unknown reason`

El handshake TLS falló. Casos típicos: el certificado de cliente no está firmado por la CA que
configuró el motor (`SCHED_GRPC_TLS_CLIENT_CA`); el simulador no confía en la CA del servidor
(`--tls-ca`); el nombre de `SCHED_INGEST_URL` no figura en el SAN del certificado del servidor
(en desarrollo, `ingest` o `localhost`); o conectas sin TLS a una ingesta que lo exige (o al revés).
Los certificados vencen: regenera con `deploy/setup-dev.sh --force`.

### El motor no arranca: `SCHED_JWT_PUBLIC_KEYS no está configurado` o `SCHED_GRPC_TLS_CERT no está configurado`

Es intencional: con la seguridad por defecto el motor no arranca sin llaves públicas ni
certificados. Configúralos (ver la [tabla de seguridad](inicio-rapido.md#seguridad)) o, solo en
desarrollo, usa `SCHED_AUTH=none` y `SCHED_GRPC_TLS=disabled`.

### La API responde `401 {"error":"unauthorized"}`

Falta `Authorization: Bearer <token>`, o el token es inválido. El cuerpo es el mismo para todo
motivo; el motivo exacto (`firma_invalida`, `vencido`, `emisor_incorrecto`, `kid_desconocido`…)
queda en el log del motor, con el `kid` y sin el token (`token rechazado: motivo=…`). Revisa que
`iss` y `aud` coincidan con `SCHED_JWT_ISSUER` y `SCHED_JWT_AUDIENCE`, que el `kid` esté entre las
llaves del motor, que `exp - iat` no supere `SCHED_JWT_MAX_TTL_S` y que los relojes estén
sincronizados (tolerancia: `SCHED_JWT_CLOCK_SKEW_S`).

### El simulador dice `error: UNAVAILABLE: io exception`

No hay una ingesta escuchando en `SCHED_INGEST_URL` (por defecto `localhost:50051`).
Comprueba que:

- el motor está corriendo con rol `ingest` o `all`;
- ya se puso al día con el historial. La ingesta abre el gRPC solo cuando está lista; mira el
  log `ingesta escuchando en gRPC :50051`;
- en Docker, el puerto `50051` está publicado (`docker compose … ps`).

### Las respuestas llegan con `stale: true`

Es esperado después de usar `seats` del simulador, porque cierra la conexión de cambios, o
después de un hueco de `seq`. Mientras tanto `/ready` responde `503` y el balanceador deja de
enviar tráfico a la réplica; si todas están `stale`, no queda ninguna lista hasta el `load`. Se corrige con el siguiente cambio en orden o con un `load`. Ver
[api-http.md](api-http.md#cuándo-stale-es-true).

### `schedules` llega vacío

- Algún curso pedido no existe en el catálogo o no tiene grupos con cupo, y entonces no hay
  combinación completa posible.
- Los grupos de los cursos pedidos se cruzan todos entre sí.

Prueba pidiendo los cursos uno por uno para encontrar el que falla.

### `seats` responde `resync_required`

El `seq` que enviaste no es el siguiente esperado. El mensaje dice cuál se esperaba
(`esperado=3`). Usa ese número o haz un `load`, que acepta cualquier `seq`.

## Docker

### Las réplicas `engine` no pasan a `healthy`

El healthcheck consulta `/ready`, que responde `200` cuando la réplica terminó de leer el
historial de NATS y tiene un catálogo fresco (por eso, con el historial vacío, no pasan a
`healthy` hasta la primera carga con `load`). Revisa los logs:

```sh
docker compose -f deploy/docker-compose.yml logs engine
docker compose -f deploy/docker-compose.yml logs nats
```

Lo habitual es que NATS no esté arriba todavía. El motor se reinicia solo (`restart:
unless-stopped`) y se recupera cuando NATS responde.

### Traefik responde `404` o `Bad Gateway`

Ninguna réplica está `healthy` todavía (ver el punto anterior). El panel de Traefik está
desactivado por seguridad; para ver qué tiene registrado, usa `docker compose … logs lb`.

### Traefik responde `429` o `413`

Es el límite por IP (`RATE_LIMIT_AVERAGE` y `RATE_LIMIT_BURST` en `deploy/.env`), el de solicitudes
simultáneas (`INFLIGHT_MAX`) o el de cuerpo (`MAX_BODY_BYTES`). Con el despliegue local todas las
solicitudes llegan desde la misma IP, así que una prueba de carga las alcanza enseguida.

### `docker compose` dice `falta deploy/.env`

Ejecuta `deploy/setup-dev.sh`: genera los certificados, las llaves del token y la contraseña de NATS.

### Traefik no descubre los contenedores

Traefik lee el socket de Docker (`/var/run/docker.sock`). En Docker Desktop (Windows y macOS)
funciona tal cual; con Docker *rootless* o Podman la ruta del socket es otra y hay que ajustar
el volumen de `lb` en [`deploy/docker-compose.yml`](../deploy/docker-compose.yml).

### Quiero empezar de cero

El historial de NATS se guarda en un volumen y sobrevive a `down`. Para borrarlo:

```sh
docker compose -f deploy/docker-compose.yml down -v
```

### Cambié el código y Docker sigue corriendo lo viejo

Hay que reconstruir la imagen:

```sh
docker compose -f deploy/docker-compose.yml up -d --build
docker compose -f deploy/docker-compose.yml build simulator   # si cambiaste el simulador
```
