#!/usr/bin/env bash
# Compara variantes del arranque en frío (por defecto SIN y CON calentamiento: SCHED_WARMUP=off|on) con bench/overflow-run.sh:
# mismas condiciones (2 núcleos fijados, recolector Serial como el Dockerfile, token JWT, tráfico que empieza apenas /ready = 200),
# corridas alternadas (off, on, off, on...). Resultado: bench/results/warmup.md.
#
#   RATE=3500 REPS=3 WORK=/tmp/warmup-compare bench/warmup-compare.sh
#   RATE=4000 REPS=3 WORK=/tmp/warmup-compare-4000 bench/warmup-compare.sh
#   # otras variantes: etiqueta:VAR=valor+VAR=valor (separadas por espacios)
#   VARIANTS="sinhttp:SCHED_WARMUP=on+SCHED_WARMUP_HTTP=off conhttp:SCHED_WARMUP=on+SCHED_WARMUP_HTTP=on" bench/warmup-compare.sh
#
# Deja $WORK/<etiqueta>-<n>/ por corrida y al final imprime bench/warmup-summary.py. Variables: RATE, REPS, WARM, MEAS, VARIANTS.
set -euo pipefail
cd "$(dirname "$0")/.."
RATE=${RATE:-3500}
REPS=${REPS:-3}
VARIANTS=${VARIANTS:-"off:SCHED_WARMUP=off on:SCHED_WARMUP=on"}
WORK=${WORK:-$(mktemp -d /tmp/sched-warmup-compare.XXXXXX)}
JAVA_HOME_DIR=${JAVA_HOME_DIR:-$(dirname "$(dirname "$(readlink -f "$(command -v java)")")")}
export JAVA_HOME_DIR
mkdir -p "$WORK"
./mvnw -q -pl server,simulation -am package -DskipTests

for n in $(seq 1 "$REPS"); do
  for variant in $VARIANTS; do
    label=${variant%%:*}; env_vars=${variant#*:}; env_vars=${env_vars//+/ }
    echo "==> $label #$n (RATE=$RATE, $env_vars) $(date +%T)"
    SKIP_BUILD=1 AUTH=jwt RATE="$RATE" READY_SLEEP=0 BOOT=2 SLICE_S=10 WARM="${WARM:-30}" MEAS="${MEAS:-60}" \
      ENGINE_OPTS="-XX:+UseSerialGC" ENGINE_ENV="$env_vars" \
      WORK="$WORK/$label-$n" bench/overflow-run.sh > "$WORK/$label-$n.out" 2>&1 || echo "falló $label #$n (ver $WORK/$label-$n.out)" >&2
    sleep 10
  done
done
args=()
for variant in $VARIANTS; do
  label=${variant%%:*}
  args+=(--label "$label=$(ls -d "$WORK/$label"-*/ | tr '\n' ',' | sed 's/,$//')")
done
python3 bench/warmup-summary.py "${args[@]}"
