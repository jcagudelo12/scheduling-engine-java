#!/usr/bin/env bash
# Una corrida con medición para investigar el desborde de la latencia con token (bench/results/profile-server.md,
# "Por qué se desborda la latencia con token"). Igual que bench/profile-server.sh (1 réplica como jar en el host, taskset,
# NATS en contenedor, k6 en contenedor, ráfaga de cupos), pero sin profiler y con parámetros para los experimentos:
#
#   AUTH=jwt RATE=3500 WORK=/tmp/x bench/overflow-run.sh
#   AUTH=jwt RATE=3500 ENGINE_OPTS="-Djdk.virtualThreadScheduler.parallelism=4" WORK=/tmp/x bench/overflow-run.sh
#   AUTH=jwt RATE=3500 ENGINE_CPUS=4,6,8,10 K6_CPUS=12-15 NATS_CPUS=0,2 STORM_CPUS=2 WORK=/tmp/x bench/overflow-run.sh
#
# Variables: AUTH (none|jwt), RATE, RAMP, WARM (calentamiento, fuera de `hold`), MEAS (ventana medida, = `hold` de k6),
# ENGINE_CPUS, NATS_CPUS, STORM_CPUS, K6_CPUS, ENGINE_HEAP, ENGINE_OPTS (flags de JVM extra), ENGINE_ENV (VAR=valor VAR=valor),
# PROBE=1 (sonda independiente de k6, PROBE_RATE/PROBE_CPUS), STORM_RATE (0 = sin ráfaga de cupos), JFR=1 (graba JFR con GC, hilos virtuales y safepoints), POLL=1 (sondea el planificador
# de hilos virtuales con jcmd y la cola de aceptación), SKIP_BUILD=1.
# Salida en $WORK: summary.json (lo que escribe bench/overflow-summary.py), k6.json, y los datos crudos de la ventana.
# Todo se detiene al terminar. Requiere lo mismo que profile-server.sh.
set -euo pipefail
export LC_ALL=C
cd "$(dirname "$0")/.."

AUTH=${AUTH:-jwt}
RATE=${RATE:-3500}
RAMP=${RAMP:-20}
BOOT=${BOOT:-12}
WARM=${WARM:-30}
MEAS=${MEAS:-60}
STORM_RATE=${STORM_RATE:-6}
ENGINE_CPUS=${ENGINE_CPUS:-4,6}
NATS_CPUS=${NATS_CPUS:-5,7}
STORM_CPUS=${STORM_CPUS:-7}
K6_CPUS=${K6_CPUS:-8-15}
ENGINE_HEAP=${ENGINE_HEAP:-768m}
ENGINE_OPTS=${ENGINE_OPTS:-}
ENGINE_ENV=${ENGINE_ENV:-}
JFR=${JFR:-0}
PROBE=${PROBE:-0}                         # 1 = sonda de latencia independiente de k6 (bench/LatencyProbe.java)
PROBE_RATE=${PROBE_RATE:-200}
PROBE_CPUS=${PROBE_CPUS:-0-3}
POLL=${POLL:-0}
JAVA_HOME_DIR=${ASPROF_DIR:-/usr/lib/jvm/java-25-amazon-corretto}
WORK=${WORK:-$(mktemp -d /tmp/sched-overflow.XXXXXX)}
HOLD=$(( WARM + MEAS ))
SIMULATOR=simulation/target/sched-simulation.jar
ENGINE=server/target/sched-server-exec.jar
ISSUER=perfil-iss; AUDIENCE=sched-engine; KID=perfil-1
mkdir -p "$WORK"

pids=()
cleanup() {
  for p in "${pids[@]}"; do kill "$p" 2>/dev/null || true; done
  docker rm -f perfil-nats perfil-k6 >/dev/null 2>&1 || true
}
trap cleanup EXIT

wait_until() { local what=$1; shift; local deadline=$(( $(date +%s) + 120 ))
  until "$@" >/dev/null 2>&1; do [ "$(date +%s)" -ge "$deadline" ] && { echo "timeout esperando $what" >&2; exit 1; }; sleep 0.2; done; }

[ "${SKIP_BUILD:-0}" = 1 ] || ./mvnw -q -pl server,simulation -am package -DskipTests
python3 bench/generate_scenario.py --profile itm > "$WORK/scenario.txt"

docker rm -f perfil-nats perfil-k6 >/dev/null 2>&1 || true
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
# shellcheck disable=SC2206
engine_env+=($ENGINE_ENV)
k6_env=()
if [ "$AUTH" = jwt ]; then
  mkdir -p "$WORK/jwt/keys"; rm -f "$WORK/jwt/keys/$KID".*
  java -jar "$SIMULATOR" keygen --out "$WORK/jwt/keys" --kid "$KID" >/dev/null
  python3 bench/profile-tokens.py --key "$WORK/jwt/keys/$KID.key" --kid "$KID" --iss "$ISSUER" --aud "$AUDIENCE" --out "$WORK/jwt" >/dev/null
  mkdir -p "$WORK/jwt/pub"; cp "$WORK/jwt/keys/$KID.pem" "$WORK/jwt/pub/"
  engine_env+=(SCHED_AUTH=jwt SCHED_JWT_PUBLIC_KEYS="$WORK/jwt/pub" SCHED_JWT_ISSUER="$ISSUER" SCHED_JWT_AUDIENCE="$AUDIENCE")
  k6_env=(-v "$WORK/jwt:/tokens:ro" -e TOKENS_COMBOS_FILE=/tokens/tokens-combos.json -e TOKENS_ALTS_FILE=/tokens/tokens-alts.json)
else
  engine_env+=(SCHED_AUTH=none)
fi

jfr_opts=()
if [ "$JFR" = 1 ]; then
  jfr_opts=("-XX:StartFlightRecording=filename=$WORK/engine.jfr,settings=profile,dumponexit=true,jdk.VirtualThreadPinned#threshold=0ms,jdk.SafepointBegin#threshold=0ms,jdk.GCPhasePause#threshold=0ms")
fi
# shellcheck disable=SC2086
env "${engine_env[@]}" taskset -c "$ENGINE_CPUS" java -Xmx"$ENGINE_HEAP" $ENGINE_OPTS "${jfr_opts[@]}" \
  --enable-native-access=ALL-UNNAMED -jar "$ENGINE" > "$WORK/engine.log" 2>&1 &
engine=$!; pids+=($engine)
wait_until "motor listo" bash -c 'curl -fs localhost:8080/ready'
sleep 3
# La primera lectura de /actuator/prometheus es lenta (clases y formato en frío) y frena a la réplica unos segundos:
# se hace antes de la carga para no medirla por error (en producción Prometheus ya la ha leído muchas veces).
[ "${PRESCRAPE:-1}" = 1 ] && { curl -s localhost:8081/actuator/prometheus >/dev/null; curl -s localhost:8081/actuator/prometheus >/dev/null; }

now_ms=$(( $(date +%s%3N) ))
start_ms=$(( now_ms + BOOT * 1000 ))
hold_start_ms=$(( start_ms + RAMP * 1000 ))
if [ "$STORM_RATE" != 0 ]; then
  SCHED_INGEST_TLS=disabled SCHED_INGEST_URL=localhost:50051 taskset -c "$STORM_CPUS" \
    java -jar "$SIMULATOR" seat-storm --plaintext --rate "$STORM_RATE" --duration "$(( HOLD + 10 ))" \
    --sections bench/drain.txt --seq 2 --start-at "$hold_start_ms" > "$WORK/storm.txt" 2>&1 &
  pids+=($!)
fi
docker run --rm --name perfil-k6 --network host --cpuset-cpus "$K6_CPUS" -u "$(id -u):$(id -g)" \
  -v "$PWD/bench:/bench" -v "$WORK:/out" -w /bench "${k6_env[@]}" \
  -e TARGET=http://localhost:8080 -e START_MS="$start_ms" -e RATE="$RATE" -e RAMP="$RAMP" -e WARM="$WARM" -e SLICE_S="${SLICE_S:-0}" -e HOLD="$HOLD" -e DOWN=5 \
  -e ALT_SHARE=0.2 -e COMBOS_FILE=students.json -e STORM_AT_MS="$hold_start_ms" -e STORM_RATE="$STORM_RATE" \
  -e E1_SAMPLE=3 -e OUT="/out/k6.json" \
  grafana/k6 run --quiet load-pico.js > "$WORK/k6.txt" 2>&1 &
k6=$!; pids+=($k6)

if [ "$PROBE" = 1 ]; then
  taskset -c "$PROBE_CPUS" java bench/LatencyProbe.java bench/students.json "$WORK/jwt/tokens-combos.json" "$PROBE_RATE" 16 \
    $(( hold_start_ms + WARM * 1000 )) $(( hold_start_ms + HOLD * 1000 )) > "$WORK/probe.txt" 2>&1 &
  pids+=($!)
fi
snap() { # $1 = 0|1: foto de CPU por hilo, CPU por núcleo, k6 y Prometheus (SNAP=none|noprom para probar si la medición frena al motor)
  local n=$1
  [ "${SNAP:-noprom}" = none ] && { date +%s%3N > "$WORK/t$n.txt"; return 0; }
  date +%s%3N > "$WORK/t$n.txt"
  for t in /proc/$engine/task/*; do cat "$t/stat" 2>/dev/null; done > "$WORK/threads$n.txt"
  grep '^cpu' /proc/stat > "$WORK/stat$n.txt"
  for p in $(pgrep -x k6 || true); do echo "$p $(cat /proc/$p/stat 2>/dev/null)"; done > "$WORK/k6proc$n.txt"
  [ "${SNAP:-noprom}" = noprom ] && return 0
  curl -s localhost:8081/actuator/prometheus > "$WORK/prom$n.txt" || true
}
sleep_until_ms() { local t=$1 n; n=$(date +%s%3N); [ "$t" -gt "$n" ] && sleep "$(awk "BEGIN{print ($t-$n)/1000}")"; return 0; }

poll_start() {
  ( while true; do
      echo "== $(date +%s%3N)"; "$JAVA_HOME_DIR/bin/jcmd" "$engine" Thread.vthread_scheduler 2>&1 | head -20
      ss -ltn 'sport = :8080' | tail -1
      sleep "${POLL_S:-2}"
    done > "$WORK/poll.txt" 2>&1 ) &
  pollpid=$!; pids+=($pollpid)
  # Colas de los sockets cada 0,25 s: Recv-Q del lado del motor (bytes sin leer: espera antes de la aplicación),
  # Send-Q del lado del motor y Recv-Q del lado del cliente (respuestas que el cliente aún no leyó).
  ( while true; do
      ss -tnH state established '( sport = :8080 or dport = :8080 )' | awk -v ts="$(date +%s%3N)" '
        { split($3, l, ":"); srv = ($3 ~ /:8080$/) }
        srv { n++; sr += $1; if ($1 > 0) snz++; ss_ += $2; if ($2 > 0) ssnz++ }
        !srv { cr += $1; if ($1 > 0) cnz++ }
        END { print ts, "conexiones=" n+0, "srv_recvq=" sr+0, "srv_recvq_conn=" snz+0, "srv_sendq=" ss_+0, "srv_sendq_conn=" ssnz+0, "cli_recvq=" cr+0, "cli_recvq_conn=" cnz+0 }'
      sleep 0.25
    done > "$WORK/sockets.txt" 2>&1 ) &
  pids+=($!)
}
if [ "${SCRAPE_EVERY:-0}" != 0 ]; then   # lecturas de /actuator/prometheus cada N s durante la meseta, con su duración (scrapes.txt)
  ( sleep_until_ms $(( hold_start_ms + ${SCRAPE_FROM:-0} * 1000 ))
    while true; do echo "$(date +%s%3N) $(curl -s -o /dev/null -w '%{time_total}' localhost:8081/actuator/prometheus)"; sleep "$SCRAPE_EVERY"; done > "$WORK/scrapes.txt" ) &
  pids+=($!)
fi
[ "$POLL" = 1 ] && [ "${POLL_FROM_START:-0}" = 1 ] && poll_start
if [ "${COLD_PROF:-0}" = 1 ]; then   # perfil de CPU (async-profiler) del arranque en frío: desde el inicio de la rampa, COLD_PROF_S segundos
  ( sleep_until_ms "$start_ms"; "$JAVA_HOME_DIR/bin/asprof" -d "${COLD_PROF_S:-40}" -e ctimer -i 1ms -o jfr -f "$WORK/cold.jfr" "$engine" > "$WORK/cold_asprof.txt" 2>&1 ) &
  pids+=($!)
fi
sleep_until_ms $(( hold_start_ms + WARM * 1000 ))
snap 0
[ "$POLL" = 1 ] && [ "${POLL_FROM_START:-0}" != 1 ] && poll_start
sleep_until_ms $(( hold_start_ms + HOLD * 1000 ))
snap 1
[ "$POLL" = 1 ] && kill "$pollpid" 2>/dev/null || true
if [ "$JFR" = 1 ]; then "$JAVA_HOME_DIR/bin/jcmd" "$engine" JFR.dump filename="$WORK/engine.jfr" >/dev/null 2>&1 || true; fi
wait "$k6" || true
sleep 1
if [ "${COLD_PROF:-0}" = 1 ] && [ -f "$WORK/cold.jfr" ]; then "$JAVA_HOME_DIR/bin/jfrconv" --cpu --threads -o collapsed "$WORK/cold.jfr" "$WORK/cold.collapsed" >/dev/null 2>&1 || true; fi
# Fin de la corrida: histogramas del motor acumulados (incluye el arranque en frío), una sola lectura, ya sin carga.
curl -s localhost:8081/actuator/prometheus > "$WORK/prom_end.txt" || true
echo "$ENGINE_CPUS $ENGINE_HEAP $ENGINE_OPTS $ENGINE_ENV" > "$WORK/config.txt"
python3 bench/overflow-summary.py "$WORK" "$ENGINE_CPUS" "$K6_CPUS" "$RATE"
