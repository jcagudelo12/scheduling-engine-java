#!/usr/bin/env bash
# Perfil del motor (async-profiler) bajo la mezcla del pico de matrícula, con 1 réplica y k6 directo.
# Mide en qué se va la CPU por solicitud: perfil de CPU, de asignación y (opcional) de espera.
#
#   bench/profile-server.sh                       # sin token (SCHED_AUTH=none), como bench/run-pico.sh
#   AUTH=jwt bench/profile-server.sh              # con JWT EdDSA, como producción
#   AUTH=jwt RATE=3000 WORK=/tmp/perfil bench/profile-server.sh
#
# El motor corre como jar en el host (no en contenedor) para poder adjuntar `asprof` sin permisos
# especiales: con 2 núcleos fijos (taskset), heap de 768 MB (lo que da MaxRAMPercentage=75 con 1 GB, igual
# que la imagen) y los mismos parámetros que docker-compose.pico.yml. NATS va en contenedor; la ingesta,
# k6 y la ráfaga de cupos, en núcleos aparte. Todo se detiene al terminar.
# Requiere: Docker, Java 25 con async-profiler 4.x (asprof y jfrconv; ASPROF_DIR), Python 3 con `cryptography` (solo AUTH=jwt).
# Salida en $WORK: <auth>_cpu.collapsed / .html, <auth>_alloc.collapsed / .html, <auth>_wall.*, <auth>_ventanas.txt, k6.
# Después: python3 bench/profile-breakdown.py $WORK/<auth>_cpu.collapsed ... (ver bench/results/profile-server.md).
set -euo pipefail
export LC_ALL=C   # awk y sleep con punto decimal
cd "$(dirname "$0")/.."

AUTH=${AUTH:-none}                       # none | jwt
RATE=${RATE:-4000}                       # solicitudes/s en la meseta
RAMP=${RAMP:-20}
BOOT=${BOOT:-15}
WARM=${WARM:-30}                         # segundos en la meseta antes de medir
BASE_S=${BASE_S:-30}                     # ventana sin profiler (CPU por solicitud de referencia)
CPU_S=${CPU_S:-60}
ALLOC_S=${ALLOC_S:-60}
WALL_S=${WALL_S:-30}                     # 0 = sin perfil de espera
STORM_RATE=${STORM_RATE:-6}
ENGINE_CPUS=${ENGINE_CPUS:-4,6}
NATS_CPUS=${NATS_CPUS:-5,7}
STORM_CPUS=${STORM_CPUS:-7}
K6_CPUS=${K6_CPUS:-8-15}
ENGINE_HEAP=${ENGINE_HEAP:-768m}
ASPROF_DIR=${ASPROF_DIR:-/usr/lib/jvm/java-25-amazon-corretto}
WORK=${WORK:-$(mktemp -d /tmp/sched-profile.XXXXXX)}
GUARD=5
HOLD=$(( WARM + BASE_S + CPU_S + ALLOC_S + WALL_S + 5 * GUARD ))
ASPROF=$ASPROF_DIR/bin/asprof
JFRCONV=$ASPROF_DIR/bin/jfrconv
SIMULATOR=simulation/target/sched-simulation.jar
ENGINE=server/target/sched-server-exec.jar
ISSUER=perfil-iss; AUDIENCE=sched-engine; KID=perfil-1
mkdir -p "$WORK" bench/results/profile
tag=$AUTH

pids=()
cleanup() {
  for p in "${pids[@]}"; do kill "$p" 2>/dev/null || true; done
  docker rm -f perfil-nats perfil-k6 >/dev/null 2>&1 || true
}
trap cleanup EXIT

echo "==> Datos con semilla fija (perfil itm) y jars"
python3 bench/generate_scenario.py --profile itm > "$WORK/scenario.txt"
./mvnw -q -pl server,simulation -am package -DskipTests

wait_until() { local what=$1; shift; local deadline=$(( $(date +%s) + 120 ))
  until "$@" >/dev/null 2>&1; do [ "$(date +%s)" -ge "$deadline" ] && { echo "timeout esperando $what" >&2; exit 1; }; sleep 0.2; done; }

echo "==> NATS e ingesta"
docker rm -f perfil-nats >/dev/null 2>&1 || true
docker run -d --name perfil-nats --cpuset-cpus "$NATS_CPUS" -p 127.0.0.1:4222:4222 nats:2 -js >/dev/null
wait_until nats bash -c 'exec 3<>/dev/tcp/127.0.0.1/4222'
SCHED_ROLE=ingest SCHED_NATS_URL=nats://127.0.0.1:4222 SCHED_AUTH=none SCHED_GRPC_TLS=disabled \
  SCHED_HTTP_PORT=8082 SCHED_MANAGEMENT_PORT=8083 LOGGING_LEVEL_ROOT=warn \
  taskset -c "$NATS_CPUS" java -Xmx256m --enable-native-access=ALL-UNNAMED -jar "$ENGINE" > "$WORK/ingest.log" 2>&1 &
pids+=($!)
wait_until ingesta bash -c 'exec 3<>/dev/tcp/127.0.0.1/50051'
for attempt in $(seq 1 50); do
  SCHED_INGEST_TLS=disabled SCHED_INGEST_URL=localhost:50051 java -jar "$SIMULATOR" load bench/scenario.json >/dev/null 2>&1 && break
  [ "$attempt" = 50 ] && { echo "no se pudo cargar el catálogo" >&2; exit 1; }
  sleep 0.3
done

engine_env=(SCHED_ROLE=query SCHED_NATS_URL=nats://127.0.0.1:4222 SCHED_MAX_COMBINATIONS=5 SCHED_SEARCH_TIMEOUT_MS=150
  LOGGING_LEVEL_ROOT=warn LOGGING_LEVEL_SCHED_SERVER=info)
k6_env=()
if [ "$AUTH" = jwt ]; then
  echo "==> Llaves y tokens JWT (fuera del repo, en $WORK/jwt)"
  mkdir -p "$WORK/jwt/keys"; rm -f "$WORK/jwt/keys/$KID".*
  java -jar "$SIMULATOR" keygen --out "$WORK/jwt/keys" --kid "$KID"
  python3 bench/profile-tokens.py --key "$WORK/jwt/keys/$KID.key" --kid "$KID" --iss "$ISSUER" --aud "$AUDIENCE" --out "$WORK/jwt"
  mkdir -p "$WORK/jwt/pub"; cp "$WORK/jwt/keys/$KID.pem" "$WORK/jwt/pub/"
  engine_env+=(SCHED_AUTH=jwt SCHED_JWT_PUBLIC_KEYS="$WORK/jwt/pub" SCHED_JWT_ISSUER="$ISSUER" SCHED_JWT_AUDIENCE="$AUDIENCE")
  k6_env=(-v "$WORK/jwt:/tokens:ro" -e TOKENS_COMBOS_FILE=/tokens/tokens-combos.json -e TOKENS_ALTS_FILE=/tokens/tokens-alts.json)
else
  engine_env+=(SCHED_AUTH=none)
fi

echo "==> Motor (núcleos $ENGINE_CPUS, heap $ENGINE_HEAP, auth=$AUTH)"
# -XX:+DebugNonSafepoints: atribución de líneas más fiel en el perfil.
env "${engine_env[@]}" taskset -c "$ENGINE_CPUS" java -Xmx"$ENGINE_HEAP" -XX:+UnlockDiagnosticVMOptions -XX:+DebugNonSafepoints \
  --enable-native-access=ALL-UNNAMED -jar "$ENGINE" > "$WORK/engine.log" 2>&1 &
engine=$!; pids+=($engine)
wait_until "motor listo" bash -c 'curl -fs localhost:8080/ready'
sleep 3

now_ms=$(( $(date +%s%3N) ))
start_ms=$(( now_ms + BOOT * 1000 ))
hold_start_ms=$(( start_ms + RAMP * 1000 ))
SCHED_INGEST_TLS=disabled SCHED_INGEST_URL=localhost:50051 taskset -c "$STORM_CPUS" \
  java -jar "$SIMULATOR" seat-storm --plaintext --rate "$STORM_RATE" --duration "$HOLD" \
  --sections bench/drain.txt --seq 2 --start-at "$hold_start_ms" > "$WORK/storm.txt" 2>&1 &
pids+=($!)
docker rm -f perfil-k6 >/dev/null 2>&1 || true
docker run --rm --name perfil-k6 --network host --cpuset-cpus "$K6_CPUS" -u "$(id -u):$(id -g)" \
  -v "$PWD/bench:/bench" -v "$WORK:/out" -w /bench "${k6_env[@]}" \
  -e TARGET=http://localhost:8080 -e START_MS="$start_ms" -e RATE="$RATE" -e RAMP="$RAMP" -e HOLD="$HOLD" -e DOWN=5 \
  -e ALT_SHARE=0.2 -e COMBOS_FILE=students.json -e STORM_AT_MS="$hold_start_ms" -e STORM_RATE="$STORM_RATE" \
  -e E1_SAMPLE=3 -e OUT="/out/${tag}_k6.json" \
  grafana/k6 run --quiet load-pico.js > "$WORK/${tag}_k6.txt" 2>&1 &
k6=$!; pids+=($k6)

# --- Ventanas de medición dentro de la meseta -------------------------------------------------------
requests() { curl -s localhost:8081/actuator/prometheus | awk '/^sched_http_latency_seconds_count/ {s+=$NF} END {printf "%d", s}'; }
cpu_ticks() { awk '{print $14+$15}' "/proc/$engine/stat"; }   # utime+stime (ticks de 10 ms)
sleep_until_ms() { local t=$1 n; n=$(date +%s%3N); [ "$t" -gt "$n" ] && sleep "$(awk "BEGIN{print ($t-$n)/1000}")"; return 0; }
: > "$WORK/${tag}_ventanas.txt"
window() { # nombre segundos [comando...]  — mide solicitudes y CPU del proceso durante la ventana
  local name=$1 secs=$2; shift 2
  local r0 c0 t0 r1 c1 t1
  r0=$(requests); c0=$(cpu_ticks); t0=$(date +%s%3N)
  if [ $# -gt 0 ]; then "$@"; else sleep "$secs"; fi
  r1=$(requests); c1=$(cpu_ticks); t1=$(date +%s%3N)
  echo "$name solicitudes=$(( r1 - r0 )) cpu_ms=$(( (c1 - c0) * 10 )) wall_ms=$(( t1 - t0 ))" | tee -a "$WORK/${tag}_ventanas.txt"
}
convert() { # jfr nombre modo(--cpu|--alloc --total|--wall)
  local jfr=$1 out=$2; shift 2
  "$JFRCONV" "$@" --threads -o collapsed "$jfr" "$out.collapsed" >/dev/null
  "$JFRCONV" "$@" -o html "$jfr" "$out.html" >/dev/null
}

sleep_until_ms $(( hold_start_ms + WARM * 1000 ))
echo "==> Meseta: ventanas de medición"
window base "$BASE_S"
sleep $GUARD
window cpu "$CPU_S" "$ASPROF" -d "$CPU_S" -e ctimer -i 1ms -o jfr -f "$WORK/${tag}_cpu.jfr" "$engine"
sleep $GUARD
window alloc "$ALLOC_S" "$ASPROF" -d "$ALLOC_S" -e alloc -o jfr -f "$WORK/${tag}_alloc.jfr" "$engine"
if [ "$WALL_S" != 0 ]; then
  sleep $GUARD
  window wall "$WALL_S" "$ASPROF" -d "$WALL_S" -e wall -i 5ms -o jfr -f "$WORK/${tag}_wall.jfr" "$engine"
fi
convert "$WORK/${tag}_cpu.jfr" "$WORK/${tag}_cpu" --cpu
convert "$WORK/${tag}_alloc.jfr" "$WORK/${tag}_alloc" --alloc --total
[ "$WALL_S" != 0 ] && convert "$WORK/${tag}_wall.jfr" "$WORK/${tag}_wall" --wall || true

curl -s localhost:8081/actuator/prometheus > "$WORK/${tag}_prom.txt" || true
echo "==> Esperando a k6"
wait "$k6" || true
true  # los HTML se copian a bench/results/profile/ a mano si pesan poco
echo "==> Listo. Salida en $WORK (copie los HTML a bench/results/profile/ si pesan poco)"
