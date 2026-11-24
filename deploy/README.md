# deploy

**El despliegue local con varias instancias y la seguridad activada, con Docker Compose.** Levanta lo mismo que
un despliegue real: NATS, una instancia de ingesta, tres de consulta y Traefik como balanceador. Es para
**desarrollo**: los secretos se generan con un script y no se versionan.

## Qué hay dentro

| Archivo o carpeta | Qué es |
|---|---|
| `docker-compose.yml` | Define los servicios: `nats`, `ingest`, `engine` (3 réplicas), `lb` (Traefik), `prometheus` (perfil `observability`) y `simulator` (solo con `run`). Los comentarios iniciales explican cada uno |
| `setup-dev.sh` | **Se corre una vez**: genera los certificados, el par de llaves del token y `deploy/.env` con la contraseña de NATS. `--force` lo regenera todo |
| `.env.example` | Plantilla de `deploy/.env`: emisor y audiencia de los tokens y los límites de Traefik (`RATE_LIMIT_*`, `INFLIGHT_MAX`, `MAX_BODY_BYTES`) |
| `certs/generate.sh` | Crea la CA de desarrollo y los certificados de la ingesta, de NATS y del cliente. Lo llama `setup-dev.sh` |
| `nats/nats.conf` | Configuración de NATS: usuarios, TLS y permisos mínimos |
| `prometheus/` | `prometheus.yml` (a quién raspa) y `rules.yml` (reglas y alertas de ejemplo) |
| `certs/`, `keys/`, `.env` | **Generados y fuera de git.** Nunca los subas |

## De qué depende y quién lo usa

- **Depende de:** la imagen del motor, que se construye con el [`Dockerfile`](../Dockerfile) de la raíz (`sched-engine-java:dev`),
  y de las imágenes públicas `nats:2`, `traefik:v3` y `prom/prometheus`.
- **Lo usa:** la persona que desarrolla (camino B del [inicio rápido](../docs/inicio-rapido.md#b-sistema-completo-con-docker)).

## Cómo correrlo

```sh
deploy/setup-dev.sh                                                   # una vez
docker compose -f deploy/docker-compose.yml up -d --build             # construye la imagen y levanta todo
docker compose -f deploy/docker-compose.yml ps                        # espera a "healthy"
docker compose -f deploy/docker-compose.yml run --rm simulator load   # carga el catálogo de ejemplo
docker compose -f deploy/docker-compose.yml down                      # apaga (el historial queda); con -v lo borra
docker compose -f deploy/docker-compose.yml config                    # solo valida el archivo (no arranca nada)
```

Puertos publicados en tu equipo: `8080` (HTTP, a través de Traefik) y `50051` (gRPC, solo con certificado de cliente).
El puerto de administración `8081` y NATS **no** se publican. Métricas (opcional):
`docker compose -f deploy/docker-compose.yml --profile observability up -d` y abre <http://localhost:9090>.

## Si algo falla

[solucion-de-problemas.md](../docs/solucion-de-problemas.md#docker): réplicas que no pasan a `healthy`, Traefik con `404`/`429`,
`falta deploy/.env`, etc.

## Para entender más

[Docker](../docs/conceptos-base.md#docker) · [Compose](../docs/conceptos-base.md#docker-compose) ·
[Healthcheck](../docs/conceptos-base.md#healthcheck) · [Traefik](../docs/conceptos-base.md#traefik) ·
[TLS y mTLS](../docs/conceptos-base.md#tls-mtls-certificados-ca-y-openssl) · [NATS](../docs/conceptos-base.md#nats-y-jetstream) ·
[ADR 0005](../docs/adr/0005-varias-instancias-con-historial-compartido.md)
