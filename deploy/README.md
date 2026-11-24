# deploy

**El despliegue local con varias instancias y la seguridad activada, con Docker Compose.** Levanta lo mismo que
un despliegue real: NATS, una instancia de ingesta, tres de consulta y Traefik como balanceador. Es para
**desarrollo**: los secretos se generan con un script y no se versionan.

## Qué hay dentro

| Archivo o carpeta | Qué es |
|---|---|
| `docker-compose.yml` | Define los servicios: `nats`, `ingest`, `engine` (3 réplicas), `lb` (Traefik), `prometheus`, `grafana` y `cadvisor` (perfil `observability`) y `simulator` (solo con `run`). Los comentarios iniciales explican cada uno |
| `setup-dev.sh` | **Se corre una vez**: genera los certificados, el par de llaves del token y `deploy/.env` con las contraseñas de NATS y de Grafana. Si `.env` ya existe, solo añade la de Grafana si falta. `--force` lo regenera todo |
| `.env.example` | Plantilla de `deploy/.env`: emisor y audiencia de los tokens y los límites de Traefik (`RATE_LIMIT_*`, `INFLIGHT_MAX`, `MAX_BODY_BYTES`) |
| `certs/generate.sh` | Crea la CA de desarrollo y los certificados de la ingesta, de NATS y del cliente. Lo llama `setup-dev.sh` |
| `nats/nats.conf` | Configuración de NATS: usuarios, TLS y permisos mínimos |
| `prometheus/` | `prometheus.yml` (a quién raspa: motores, ingesta y cAdvisor), `rules.yml` (reglas y alertas de ejemplo) y `prometheus-bench.yml` (lo mismo para la prueba de carga, ver [`bench/`](../bench/README.md#seguir-una-prueba-en-vivo)) |
| `grafana/` | Grafana como código: `provisioning/` (origen de datos Prometheus y carga de tableros) y `dashboards/motor-de-horarios.json`, el tablero "Motor de horarios". Lo usan este despliegue y `bench/docker-compose.observe.yml` |
| `certs/`, `keys/`, `.env` | **Generados y fuera de git.** Nunca los subas |

## De qué depende y quién lo usa

- **Depende de:** la imagen del motor, que se construye con el [`Dockerfile`](../Dockerfile) de la raíz (`sched-engine-java:dev`),
  y de las imágenes públicas `nats:2`, `traefik:v3`, `prom/prometheus:v3.16.0`, `grafana/grafana:13.3.1` y
  `ghcr.io/google/cadvisor:v0.61.1` (las tres últimas solo con el perfil `observability`).
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
El puerto de administración `8081` y NATS **no** se publican.

**Ver el motor en el navegador (opcional, solo desarrollo).**

```sh
docker compose -f deploy/docker-compose.yml --profile observability up -d   # Prometheus, Grafana y cAdvisor
docker compose -f deploy/docker-compose.yml --profile observability down    # apagarlos (con el resto)
```

- **Grafana:** <http://127.0.0.1:3000>, usuario `admin`, contraseña `GRAFANA_ADMIN_PASSWORD` de `deploy/.env`
  (la crea `setup-dev.sh`; sin ella Grafana no arranca ni deja entrar). Sin acceso anónimo, sin registro de usuarios y sin
  telemetría. Si el 3000 está ocupado: `GRAFANA_PORT=3001 docker compose ... up -d`. Se abre directo en el tablero
  "Motor de horarios". Guía paso a paso: [inicio rápido](../docs/inicio-rapido.md#ver-el-motor-en-el-navegador).
- **Prometheus:** <http://127.0.0.1:9090>. **cAdvisor** no publica ningún puerto: solo Prometheus lo consulta.
- **Riesgo aceptado solo para desarrollo:** cAdvisor monta en solo lectura `/`, `/sys`, `/var/run` y `/var/lib/docker`
  del equipo para medir los contenedores. No lo uses así en un servidor real.
- Grafana no guarda estado (sin volumen): el origen de datos y el tablero salen siempre de `deploy/grafana/`; lo que
  cambies en la interfaz no persiste. Para cambiar el tablero, edita el JSON.

## Si algo falla

[solucion-de-problemas.md](../docs/solucion-de-problemas.md#docker): réplicas que no pasan a `healthy`, Traefik con `404`/`429`,
`falta deploy/.env`, etc.

## Para entender más

[Docker](../docs/conceptos-base.md#docker) · [Compose](../docs/conceptos-base.md#docker-compose) ·
[Healthcheck](../docs/conceptos-base.md#healthcheck) · [Traefik](../docs/conceptos-base.md#traefik) ·
[TLS y mTLS](../docs/conceptos-base.md#tls-mtls-certificados-ca-y-openssl) · [NATS](../docs/conceptos-base.md#nats-y-jetstream) ·
[ADR 0005](../docs/adr/0005-varias-instancias-con-historial-compartido.md)
