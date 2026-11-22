#!/usr/bin/env bash
# Genera una CA y los certificados para DESARROLLO (nunca para producción):
#   - servidor de la ingesta gRPC (SAN: ingest, localhost, 127.0.0.1)
#   - servidor de NATS            (SAN: nats, localhost, 127.0.0.1)
#   - cliente de la institución simulada (autenticación mutua)
#
#   deploy/certs/generate.sh           # crea lo que falta
#   deploy/certs/generate.sh --force   # regenera todo
#
# Resultado (todo ignorado por git):
#   deploy/certs/ca/       ca.crt, ca.key        la llave de la CA no se monta en ningún contenedor
#   deploy/certs/ingest/   server.crt, server.key, ca.crt   (ingest: certificado propio y CA de clientes)
#   deploy/certs/nats/     server.crt, server.key           (nats)
#   deploy/certs/engine/   ca.crt                           (instancias que confían en el certificado de NATS)
#   deploy/certs/client/   client.crt, client.key, ca.crt   (institución simulada)
#
# Las llaves quedan legibles (0644) porque los contenedores corren con un usuario sin privilegios
# distinto al dueño del archivo; es aceptable solo para desarrollo local. En producción cada
# parte genera su llave y pide su certificado a la CA correspondiente.
set -euo pipefail

command -v openssl >/dev/null || { echo "se requiere openssl" >&2; exit 1; }
cd "$(dirname "$0")"

if [ "${1:-}" = "--force" ]; then
  rm -rf ca ingest nats engine client
fi
if [ -f ca/ca.crt ] && [ -f ingest/server.crt ] && [ -f nats/server.crt ] && [ -f client/client.crt ]; then
  echo "certificados ya generados en $(pwd) (use --force para regenerarlos)"
  exit 0
fi
rm -rf ca ingest nats engine client
mkdir -p ca ingest nats engine client

DAYS=825
new_key() { openssl genpkey -algorithm EC -pkeyopt ec_paramgen_curve:P-256 -out "$1" 2>/dev/null; }

# CA
new_key ca/ca.key
openssl req -x509 -new -key ca/ca.key -sha256 -days "$DAYS" -subj "/CN=sched-dev-ca" \
  -addext "basicConstraints=critical,CA:TRUE" -addext "keyUsage=critical,keyCertSign,cRLSign" \
  -out ca/ca.crt

# issue <directorio> <nombre> <CN> <extendedKeyUsage> <subjectAltName|->
issue() {
  local dir=$1 name=$2 cn=$3 eku=$4 san=$5 ext
  ext=$(mktemp)
  {
    echo "basicConstraints=CA:FALSE"
    echo "keyUsage=critical,digitalSignature"
    echo "extendedKeyUsage=$eku"
    [ "$san" = "-" ] || echo "subjectAltName=$san"
  } > "$ext"
  new_key "$dir/$name.key"
  openssl req -new -key "$dir/$name.key" -subj "/CN=$cn" -out "$dir/$name.csr"
  openssl x509 -req -in "$dir/$name.csr" -CA ca/ca.crt -CAkey ca/ca.key -CAcreateserial \
    -sha256 -days "$DAYS" -extfile "$ext" -out "$dir/$name.crt" 2>/dev/null
  rm -f "$dir/$name.csr" "$ext"
}

issue ingest server ingest serverAuth "DNS:ingest,DNS:localhost,IP:127.0.0.1"
issue nats server nats serverAuth "DNS:nats,DNS:localhost,IP:127.0.0.1"
issue client client institucion-dev clientAuth -

cp ca/ca.crt ingest/ca.crt
cp ca/ca.crt engine/ca.crt
cp ca/ca.crt client/ca.crt
rm -f ca/ca.srl
chmod 644 ingest/*.key nats/*.key client/*.key
chmod 600 ca/ca.key

echo "certificados de desarrollo generados en $(pwd)"
