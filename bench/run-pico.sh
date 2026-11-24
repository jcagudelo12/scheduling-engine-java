#!/usr/bin/env bash
# Prueba del pico de matrícula: apertura de una ventana (rampa, meseta, descenso) con la mezcla
# de /combinations y /alternatives y una ráfaga de cupos que vacía grupos populares.
#
#   bench/run-pico.sh                                    # 1 réplica a 1.000-8.000/s y 3 réplicas a 4.000-12.000/s
#   STEPS="1:1000 3:4000" bench/run-pico.sh              # solo esos pasos (réplicas:tasa)
#   MODE=worst bench/run-pico.sh                         # peor caso 7x8 con SCHED_SEARCH_TIMEOUT_MS=150
#   DIRECT=1 PREFIX=directo STEPS="1:8000" bench/run-pico.sh   # k6 contra la réplica, sin pasar por Traefik
#
# El motor calienta la JVM antes de que /ready responda 200 (SCHED_WARMUP=on por defecto, ver bench/results/warmup.md) y el paso espera a
# que las réplicas estén "healthy" (healthcheck = /ready): la carga empieza con la réplica caliente. `SCHED_WARMUP=off bench/run-pico.sh`
# reproduce el arranque en frío de bench/results/report-pico.md (medido antes del calentamiento).
#
# Cada paso levanta todo de cero (NATS vacío, motores recién iniciados, catálogo `--profile itm`),
# con cada réplica en núcleos fijos. Resultados crudos: bench/results/<prefijo>_*.{json,txt}
# (ignorados por git). Resumen: python3 bench/report_pico.py <prefijo>... (ver bench/README.md).
set -euo pipefail
cd "$(dirname "$0")/.."

MODE=${MODE:-pico}                    # pico | worst
RAMP=${RAMP:-40}
HOLD=${HOLD:-90}
DOWN=${DOWN:-20}
BOOT=${BOOT:-12}                      # segundos que se dan a k6 para arrancar (lee los datos) antes de la rampa
STORM_RATE=${STORM_RATE:-10}          # grupos vaciados por segundo durante la meseta (0 = sin ráfaga)
SEARCH_TIMEOUT_MS=${SEARCH_TIMEOUT_MS:-150}
MAX_COMBINATIONS=${MAX_COMBINATIONS:-5}
E1_SAMPLE=${E1_SAMPLE:-3}
OUT=${OUT:-bench/results}             # dentro de bench/ (se monta en el contenedor de k6)
export MAX_COMBINATIONS
export SEARCH_TIMEOUT_MS
export ENGINE1_CPUS=${ENGINE1_CPUS:-4,6}
export ENGINE2_CPUS=${ENGINE2_CPUS:-8,10}
export ENGINE3_CPUS=${ENGINE3_CPUS:-12,14}
export LB_CPUS=${LB_CPUS:-0,1,2,3}   # 2 núcleos para Traefik: con 1 se satura cerca de 4.000/s
export NATS_CPUS=${NATS_CPUS:-5,7}   # NATS e ingesta (poco trabajo: solo la ráfaga de cupos)
# k6 usa los núcleos que quedan libres: con 1 réplica, 8-15; con 3, los hermanos de hiperhilo de los motores.
K6_CPUS_1R=${K6_CPUS_1R:-8-15}
K6_CPUS_3R=${K6_CPUS_3R:-5,7,9,11,13,15}
DIRECT=${DIRECT:-0}                  # 1: k6 contra engine1:8080, sin Traefik (solo con 1 réplica)
STORM_CPUS=${STORM_CPUS:-7}

if [ "$MODE" = worst ]; then
  PREFIX=${PREFIX:-worst}
  STEPS=${STEPS:-"1:50 1:100 1:200"}   # solo perfiles 7x8: la tasa es mucho menor
  COMBOS_FILE=${WORST_FILE:-worst.json}; ALT_SHARE=0; STORM_RATE=0
else
  PREFIX=${PREFIX:-pico}
  STEPS=${STEPS:-"1:1000 1:2000 1:4000 1:8000 3:4000 3:8000 3:12000"}
  COMBOS_FILE=students.json; ALT_SHARE=${ALT_SHARE:-0.2}
fi

HTTP=18080
GRPC=15051
C="docker compose -f bench/docker-compose.pico.yml"
SIMULATOR=simulation/target/sched-simulation.jar
mkdir -p "$OUT"

echo "==> Datos con semilla fija (perfil itm)"
python3 bench/generate_scenario.py --profile itm | tee "$OUT/${PREFIX}_scenario.txt"
./mvnw -q -pl simulation -am package -DskipTests

wait_until() { # descripción comando... — espera hasta 120 s
  local what=$1; shift
  local deadline=$(( $(date +%s) + 120 ))
  until "$@" >/dev/null 2>&1; do
    [ "$(date +%s)" -ge "$deadline" ] && { echo "timeout esperando $what" >&2; exit 1; }
    sleep 0.2
  done
}
healthy() { [ "$(docker inspect -f '{{.State.Health.Status}}' "$1" 2>/dev/null)" = healthy ]; }
http_ok() { [ "$(curl -s -o /dev/null -w '%{http_code}' -X POST "http://localhost:$HTTP/combinations" \
  -H 'content-type: application/json' -d '{"student_id":"x","eligible_courses":["C0000"]}')" = 200 ]; }

{
  echo "## Entorno"; echo; echo "| | |"; echo "|---|---|"
  echo "| Fecha | $(date '+%Y-%m-%d %H:%M') |"
  echo "| CPU | $(grep -m1 'model name' /proc/cpuinfo | cut -d: -f2 | xargs) ($(nproc) hilos) |"
  echo "| Memoria | $(free -g | awk '/Mem:/ {print $2}') GB |"
  echo "| Kernel | $(uname -r) |"
  echo "| Modo de frecuencia | $(cat /sys/devices/system/cpu/cpu0/cpufreq/scaling_governor 2>/dev/null || echo desconocido) |"
  echo "| Docker | $(docker version --format '{{.Server.Version}}') |"
  echo "| Imagen | $(docker image inspect -f '{{.Id}}' sched-engine-java:dev | cut -c8-19) |"
  echo "| Java | $(docker run --rm --entrypoint java sched-engine-java:dev -version 2>&1 | head -1) |"
  echo "| k6 | $(docker run --rm grafana/k6 version 2>/dev/null | head -1) |"
  echo "| Traefik | $(docker run --rm traefik:v3 version 2>/dev/null | awk '/Version/ {print $2; exit}') |"
  echo "| Núcleos | motor 1 \`$ENGINE1_CPUS\`, motor 2 \`$ENGINE2_CPUS\`, motor 3 \`$ENGINE3_CPUS\`, Traefik \`$LB_CPUS\`, NATS e ingesta \`$NATS_CPUS\`, k6 \`$K6_CPUS_1R\` (1 réplica) o \`$K6_CPUS_3R\` (3), ráfaga \`$STORM_CPUS\` |"
  echo "| Motor | 2 CPUs y 1 GB por réplica; SCHED_SEARCH_TIMEOUT_MS=$SEARCH_TIMEOUT_MS, SCHED_MAX_COMBINATIONS=$MAX_COMBINATIONS |"
  echo "| Camino de k6 | $([ "$DIRECT" = 1 ] && echo 'directo a la réplica, sin Traefik' || echo 'Traefik, con 2 núcleos (4 hilos)') |"
  echo "| Forma de la carga | rampa ${RAMP} s, meseta ${HOLD} s, descenso ${DOWN} s; ráfaga de cupos: $STORM_RATE grupos/s en la meseta |"
} > "$OUT/${PREFIX}_environment.md"

sample() { # archivo — CPU y memoria de los contenedores de la prueba (incluido k6, cuando ya existe)
  local file=$1
  while :; do
    docker stats --no-stream --format "$(date +%s) {{.Name}} {{.CPUPerc}} {{.MemUsage}}" \
      $(docker ps -q --filter name=bench-pico-) >> "$file" 2>/dev/null
  done
}

for step in $STEPS; do
  replicas=${step%%:*}; rate=${step##*:}
  tag="${PREFIX}_r${replicas}_t${rate}"
  rm -f "$OUT/${tag}.json" "$OUT/${tag}"_*
  k6_cpus=$K6_CPUS_1R; [ "$replicas" = 3 ] && k6_cpus=$K6_CPUS_3R
  target="http://lb:80"; [ "$DIRECT" = 1 ] && target="http://engine1:8080"
  echo "==> $replicas réplica(s), pico de $rate solicitudes/s"

  profile=(); engines=(engine1)
  if [ "$replicas" = 3 ]; then profile=(--profile r3); engines=(engine1 engine2 engine3); fi
  $C --profile r3 down -v >/dev/null 2>&1 || true
  $C "${profile[@]}" up -d nats ingest "${engines[@]}" lb >/dev/null 2>&1
  wait_until "ingesta" bash -c "exec 3<>/dev/tcp/localhost/$GRPC"
  for attempt in $(seq 1 50); do
    SCHED_INGEST_TLS=disabled SCHED_INGEST_URL="localhost:$GRPC" java -jar "$SIMULATOR" load bench/scenario.json >/dev/null 2>&1 && break
    [ "$attempt" = 50 ] && { echo "no se pudo cargar el catálogo" >&2; exit 1; }
    sleep 0.3
  done
  for e in "${engines[@]}"; do wait_until "$e sano" healthy "bench-pico-$e-1"; done
  wait_until "Traefik" http_ok
  sleep 3
  : > "$OUT/${tag}_stats.txt"

  # k6 espera hasta start_ms (setup) para que la rampa y la ráfaga de cupos queden alineadas.
  now_ms=$(( $(date +%s%3N) ))
  start_ms=$(( now_ms + BOOT * 1000 ))
  hold_start=$(( start_ms + RAMP * 1000 ))
  echo "$hold_start" > "$OUT/${tag}_hold_start_ms.txt"
  if [ "$STORM_RATE" != 0 ]; then
    SCHED_INGEST_TLS=disabled SCHED_INGEST_URL="localhost:$GRPC" taskset -c "$STORM_CPUS" \
      java -jar "$SIMULATOR" seat-storm --plaintext --rate "$STORM_RATE" --duration "$HOLD" \
      --sections bench/drain.txt --seq 2 --start-at "$hold_start" > "$OUT/${tag}_storm.txt" 2>&1 &
    storm=$!
  fi
  sample "$OUT/${tag}_stats.txt" & sampler=$!
  docker run --rm --name bench-pico-k6 --network bench-pico_default --cpuset-cpus "$k6_cpus" \
    -u "$(id -u):$(id -g)" -v "$PWD/bench:/bench" -w /bench \
    -e TARGET="$target" -e START_MS="$start_ms" -e RATE="$rate" -e RAMP="$RAMP" -e HOLD="$HOLD" -e DOWN="$DOWN" \
    -e ALT_SHARE="$ALT_SHARE" -e COMBOS_FILE="$COMBOS_FILE" -e STORM_AT_MS="$hold_start" \
    -e STORM_RATE="$STORM_RATE" -e E1_SAMPLE="$E1_SAMPLE" -e OUT="/${OUT}/${tag}.json" \
    grafana/k6 run --quiet load-pico.js > "$OUT/${tag}_k6.txt" 2>&1 || true
  kill "$sampler" 2>/dev/null; wait "$sampler" 2>/dev/null || true
  [ "$STORM_RATE" != 0 ] && { wait "$storm" 2>/dev/null || true; }

  for e in "${engines[@]}"; do
    docker exec "bench-pico-$e-1" curl -s localhost:8081/actuator/prometheus > "$OUT/${tag}_prom_$e.txt" || true
    docker inspect -f '{{.RestartCount}} {{.State.OOMKilled}}' "bench-pico-$e-1" > "$OUT/${tag}_state_$e.txt" || true
  done
  $C --profile r3 down -v >/dev/null 2>&1 || true
done

echo "==> Listo. Resumen: python3 bench/report_pico.py $OUT $PREFIX"
