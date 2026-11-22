#!/usr/bin/env bash
# Prepara todo lo que necesita deploy/docker-compose.yml en desarrollo y que no se versiona:
#   - deploy/certs/  CA y certificados (gRPC de ingesta con mTLS, NATS con TLS, cliente simulado)
#   - deploy/keys/   par Ed25519 para firmar y verificar tokens (public/ la monta el motor;
#                    private/ queda en su máquina, para firmar tokens de prueba)
#   - deploy/.env    contraseña de NATS (aleatoria) y demás variables
#
#   deploy/setup-dev.sh            # crea lo que falta
#   deploy/setup-dev.sh --force    # regenera todo (cambia las credenciales)
#
# SOLO DESARROLLO. En producción la institución gestiona sus propias llaves y certificados.
set -euo pipefail

command -v openssl >/dev/null || { echo "se requiere openssl" >&2; exit 1; }
cd "$(dirname "$0")"
FORCE=${1:-}
KID=dev-1

echo "==> Certificados"
certs/generate.sh ${FORCE:+"$FORCE"}

echo "==> Llaves del token (Ed25519, kid=$KID)"
if [ "$FORCE" = "--force" ]; then rm -rf keys; fi
if [ -f "keys/private/$KID.key" ] && [ -f "keys/public/$KID.pem" ]; then
  echo "llaves ya generadas en $(pwd)/keys (use --force para regenerarlas)"
else
  rm -rf keys
  mkdir -p keys/public keys/private
  openssl genpkey -algorithm ed25519 -out "keys/private/$KID.key"
  openssl pkey -in "keys/private/$KID.key" -pubout -out "keys/public/$KID.pem"
  chmod 600 "keys/private/$KID.key"
  chmod 644 "keys/public/$KID.pem"
  chmod 755 keys keys/public
  chmod 700 keys/private
fi

echo "==> deploy/.env"
if [ -f .env ] && [ "$FORCE" != "--force" ]; then
  echo ".env ya existe (use --force para regenerarlo)"
else
  # La contraseña empieza con una letra: NATS interpreta como número un valor que lo parezca.
  password="n$(openssl rand -hex 24)"
  sed "s|^NATS_ENGINE_PASSWORD=.*|NATS_ENGINE_PASSWORD=$password|" .env.example > .env
  chmod 600 .env
  echo ".env creado con una contraseña de NATS nueva"
fi

cat <<MSG

Listo. Siguientes pasos:
  docker compose -f deploy/docker-compose.yml up -d --build
  docker compose -f deploy/docker-compose.yml run --rm simulator load
  # token de prueba y consulta (ver docs/inicio-rapido.md):
  java -jar simulation/target/sched-simulation.jar token --key deploy/keys/private/$KID.key --kid $KID \\
    --student est-001 --courses MAT101,FIS101 --iss institucion-dev --aud sched-engine
MSG
