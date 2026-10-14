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
docker run -d --rm --name sched-nats -p 4222:4222 nats:2 -js
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

`/ready` puede estar en `200` y aun así no haber catálogo: ver [api-http.md](api-http.md#sondas).

### El simulador dice `error: UNAVAILABLE: io exception`

No hay una ingesta escuchando en `SCHED_INGEST_URL` (por defecto `localhost:50051`).
Comprueba que:

- el motor está corriendo con rol `ingest` o `all`;
- ya se puso al día con el historial. La ingesta abre el gRPC solo cuando está lista; mira el
  log `ingesta escuchando en gRPC :50051`;
- en Docker, el puerto `50051` está publicado (`docker compose … ps`).

### Las respuestas llegan con `stale: true`

Es esperado después de usar `seats` del simulador, porque cierra la conexión de cambios, o
después de un hueco de `seq`. Se corrige con el siguiente cambio en orden o con un `load`. Ver
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
historial de NATS. Revisa los logs:

```sh
docker compose -f deploy/docker-compose.yml logs engine
docker compose -f deploy/docker-compose.yml logs nats
```

Lo habitual es que NATS no esté arriba todavía. El motor se reinicia solo (`restart:
unless-stopped`) y se recupera cuando NATS responde.

### Traefik responde `404` o `Bad Gateway`

Ninguna réplica está `healthy` todavía (ver el punto anterior). En el panel
<http://localhost:8090> se ven las réplicas que Traefik tiene registradas.

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
