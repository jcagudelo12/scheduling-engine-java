#!/usr/bin/env python3
"""Resume corridas de bench/overflow-run.sh hechas para comparar el arranque en frío con y sin calentamiento
(bench/results/warmup.md). Lee de cada directorio WORK: summary.json, k6.json, times.txt, ready_ms.txt, jit_series.txt,
loadavg.txt y config.txt.

  warmup-summary.py WORK [WORK ...]        # una fila por corrida (JSON por línea) y la tabla en markdown al final
  warmup-summary.py --label off=WORK1,WORK2 --label on=WORK3,WORK4    # agrupa y calcula media y rango por configuración

Los tramos de 10 s se cuentan desde el inicio de la rampa de k6 (START_MS, que con READY_SLEEP=0 y BOOT=2 es ~2 s después
de /ready = 200): 0-10, 10-20, 20-30 s son la fase fría; el estado estable es la meseta medida de k6 (`phase:hold`,
desde el segundo 20 + WARM). La CPU del JIT sale de los hilos "C1/C2 CompilerThread" (ticks de /proc, 100 Hz).
"""
import json, re, statistics, sys
from pathlib import Path

HZ = 100.0


def load_run(work):
    work = Path(work)
    summary = json.loads((work / "summary.json").read_text())
    k6 = json.loads((work / "k6.json").read_text())["metrics"]
    engine_start, ready, start = map(int, (work / "times.txt").read_text().split())
    # jit_series.txt: "ts tid:ticks tid:ticks ..." (solo hilos del compilador). Los hilos del compilador aparecen y desaparecen
    # (UseDynamicNumberOfCompilerThreads): se conserva el último valor visto de cada uno, así la CPU acumulada nunca baja.
    series = []
    last = {}
    for line in (work / "jit_series.txt").read_text().splitlines():
        f = line.split()
        if len(f) < 1 or not f[0].isdigit() or int(f[0]) == 0:
            continue
        if any(":" not in item for item in f[1:]):   # formato anterior (sumas): no sirve para la CPU del JIT por tramo
            series = []
            break
        for item in f[1:]:
            tid, ticks = item.split(":")
            last[tid] = max(last.get(tid, 0), int(ticks))
        series.append((int(f[0]), sum(last.values()) / HZ))

    def cum(idx, t_ms):  # CPU acumulada de los hilos del JIT, interpolada (s) en el instante t_ms
        if not series:
            return float("nan")
        prev = (engine_start, 0.0)
        for row in series:
            if row[0] >= t_ms:
                span = row[0] - prev[0]
                return prev[1] + (row[1] - prev[1]) * ((t_ms - prev[0]) / span if span else 1)
            prev = row
        return series[-1][1]

    def jit_between(a_ms, b_ms):
        return cum(1, b_ms) - cum(1, a_ms)

    def val(key):
        v = k6.get(key, {})
        return v.get("values", v)

    out = {"work": str(work), "ready_s": round((ready - engine_start) / 1000, 1), "rate": summary["rate"]}
    out["jit_boot_to_ready_s"] = round(jit_between(engine_start, ready), 1)
    out["jit_ready_to_start_s"] = round(jit_between(ready, start), 1)
    for n in range(3):
        a, b = start + n * 10_000, start + (n + 1) * 10_000
        out[f"jit_{n*10}_{(n+1)*10}_s"] = round(jit_between(a, b), 2)
        d = val(f"http_req_duration{{slice:{n}}}")
        out[f"p50_{n}"] = round(d.get("med", float("nan")), 2)
        out[f"p99_{n}"] = round(d.get("p(99)", float("nan")), 1)
        f = val(f"http_req_failed{{slice:{n}}}")
        out[f"err_{n}"] = f.get("passes", 0)          # solicitudes con estado distinto de 200 y 422 en el tramo
        r = val(f"http_reqs{{slice:{n}}}")
        out[f"reqs_{n}"] = r.get("count", 0)
    # JIT en la meseta medida (estado estable): de rampa + WARM en adelante, hasta el final de la meseta.
    t0, t1 = int((Path(work) / "t0.txt").read_text()), int((Path(work) / "t1.txt").read_text())
    out["jit_steady_cores"] = round(jit_between(t0, t1) / ((t1 - t0) / 1000), 3)
    out["p50_steady"] = summary["p50_ms"]
    out["p95_steady"] = summary["p95_ms"]
    out["p99_steady"] = summary["p99_ms"]
    out["dropped"] = summary["dropped_iterations"]
    out["vus_max"] = summary["vus_max"]
    out["engine_cpu_pct"] = summary["engine_cpu_pct_of_set"]
    out["req_per_s"] = summary["hold_req_per_s"]
    la = (work / "loadavg.txt").read_text().splitlines()
    # Carga media de 1 minuto de `uptime` (al empezar y al terminar la corrida); la coma decimal depende del locale.
    out["load"] = [re.search(r"load average: (\d+[.,]\d+)", l).group(1).replace(",", ".") for l in la]
    return out


def fmt(rows, key, digits=1):
    vals = [r[key] for r in rows]
    if len(vals) == 1:
        return f"{vals[0]:.{digits}f}"
    mean = statistics.mean(vals)
    return f"{mean:.{digits}f} ({min(vals):.{digits}f}-{max(vals):.{digits}f})"


def main(argv):
    groups = {}
    if argv and argv[0] == "--label":
        i = 0
        while i < len(argv):
            if argv[i] == "--label":
                label, dirs = argv[i + 1].split("=", 1)
                groups[label] = dirs.split(",")
                i += 2
            else:
                i += 1
    else:
        groups["corridas"] = argv
    for label, dirs in groups.items():
        rows = [load_run(d) for d in dirs]
        for r in rows:
            print(label, json.dumps(r, ensure_ascii=False))
        print(f"\n{label} (n={len(rows)}, media y rango)")
        for key in ("ready_s", "jit_boot_to_ready_s", "jit_ready_to_start_s", "jit_0_10_s", "jit_10_20_s", "jit_20_30_s",
                    "jit_steady_cores", "p50_0", "p99_0", "p50_1", "p99_1", "p50_2", "p99_2", "err_0", "err_1", "err_2",
                    "p50_steady", "p99_steady", "dropped", "vus_max", "engine_cpu_pct"):
            print(f"  {key}: {fmt(rows, key, 3 if key == 'jit_steady_cores' else 1)}")


if __name__ == "__main__":
    main(sys.argv[1:])
