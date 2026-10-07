#!/usr/bin/env bash
# Microbenchmarks (solo el cálculo, sin HTTP) con JMH.
# Guarda los tiempos en bench/results/micro.json para bench/plot.py.
set -euo pipefail
cd "$(dirname "$0")/.."
OUT=${OUT:-bench/results}
mkdir -p "$OUT"

./mvnw -q -pl benchmarks -am package -DskipTests
java -jar benchmarks/target/benchmarks.jar -rf json -rff "$OUT/jmh.json" >/dev/null 2>&1

python3 - "$OUT" <<'PY'
import json, sys
from pathlib import Path

out = Path(sys.argv[1])
jmh = {}
for r in json.loads((out / "jmh.json").read_text()):
    score, unit = r["primaryMetric"]["score"], r["primaryMetric"]["scoreUnit"]
    us = score / 1000 if unit.startswith("ns") else score
    jmh[(r["benchmark"].rsplit(".", 1)[-1], r.get("params", {}).get("courses"))] = us

micro = {
    "backtracking_6x5": {"java": jmh[("backtracking6x5", None)]},
    # 1.000 cursos = 5.000 grupos en el catálogo.
    "catalog_lookup_5000": {"java": jmh[("openSectionsOf", "1000")]},
}
(out / "micro.json").write_text(json.dumps(micro, indent=2))
print(json.dumps(micro, indent=2))
PY
