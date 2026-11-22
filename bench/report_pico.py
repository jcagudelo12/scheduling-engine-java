#!/usr/bin/env python3
"""Resume las corridas de bench/run-pico.sh en tablas Markdown.

Uso: python3 bench/report_pico.py <carpeta> <prefijo> [segundos de meseta (90)]

Lee <prefijo>_r<réplicas>_t<tasa>.json (resumen de k6), *_stats.txt (CPU y memoria de los
contenedores), *_prom_*.txt (métricas Prometheus de cada réplica) y *_storm.txt (ráfaga de cupos).
Todo se mide en la meseta (phase=hold); la rampa y el descenso no cuentan.
"""

import json
import re
import sys
from pathlib import Path

out = Path(sys.argv[1])
prefix = sys.argv[2]
hold = float(sys.argv[3]) if len(sys.argv) > 3 else 90.0


def ms(v):
    if v is None:
        return "–"
    return f"{v:.2f}" if v < 10 else f"{v:.0f}"


def val(metrics, name, key):
    return metrics.get(name, {}).get("values", {}).get(key)


def pct(passes, fails):
    total = (passes or 0) + (fails or 0)
    return 100.0 * (passes or 0) / total if total else None


def prom_sum(files, metric, **labels):
    total, found = 0.0, False
    for f in files:
        for line in f.read_text().splitlines():
            if line.startswith(metric + "{") and all(f'{k}="{v}"' in line for k, v in labels.items()):
                total += float(line.rsplit(" ", 1)[1])
                found = True
    return total if found else None


def cpu_in_window(stats, start_s, end_s):
    """CPU medio (%) por contenedor dentro de la meseta."""
    acc = {}
    for line in stats.read_text().splitlines() if stats.exists() else []:
        parts = line.split()
        if len(parts) < 3 or not parts[2].endswith("%"):
            continue
        t, name = int(parts[0]), parts[1]
        if start_s <= t <= end_s:
            acc.setdefault(name, []).append(float(parts[2][:-1]))
    return {n: sum(v) / len(v) for n, v in acc.items()}


def mem_max(stats, start_s, end_s, name):
    best = 0.0
    for line in stats.read_text().splitlines() if stats.exists() else []:
        parts = line.split()
        if len(parts) >= 4 and parts[1] == name and start_s <= int(parts[0]) <= end_s:
            m = re.match(r"([\d.]+)(KiB|MiB|GiB)", parts[3])
            if m:
                best = max(best, float(m.group(1)) * {"KiB": 1 / 1024, "MiB": 1, "GiB": 1024}[m.group(2)])
    return best


runs = []
for f in sorted(out.glob(f"{prefix}_r*_t*.json"), key=lambda p: tuple(int(x) for x in re.findall(r"\d+", p.stem)[-2:])):
    m = re.search(r"_r(\d+)_t(\d+)\.json$", f.name)
    runs.append((int(m.group(1)), int(m.group(2)), f))

env = out / f"{prefix}_environment.md"
if env.exists():
    print(env.read_text().strip() + "\n")

rows, e1_rows, cpu_rows, prom_rows = [], [], [], []
for replicas, rate, f in runs:
    tag = f"{prefix}_r{replicas}_t{rate}"
    m = json.loads(f.read_text())["metrics"]
    h = "{phase:hold}"
    reqs = sum(val(m, f"http_reqs{{phase:hold,endpoint:{e}}}", "count") or 0 for e in ("combinations", "alternatives"))
    dur = lambda name, key: val(m, name, key)
    fails = val(m, f"http_req_failed{h}", "passes") or 0
    comb = f"http_req_duration{{phase:hold,endpoint:combinations}}"
    alt = f"http_req_duration{{phase:hold,endpoint:alternatives}}"
    inc = pct(val(m, f"incomplete{h}", "passes"), val(m, f"incomplete{h}", "fails"))
    stale = pct(val(m, f"stale{h}", "passes"), val(m, f"stale{h}", "fails"))
    r422 = pct(val(m, f"rejected_422{h}", "passes"), val(m, f"rejected_422{h}", "fails"))
    dropped = val(m, "dropped_iterations", "count") or 0
    ok = (dur(f"http_req_duration{h}", "p(99)") or 1e9) < 200 and fails / max(reqs, 1) < 0.001 and dropped == 0
    rows.append(
        f"| {replicas} | {rate} | {reqs / hold:.0f} | {ms(dur(f'http_req_duration{h}', 'med'))} "
        f"| {ms(dur(f'http_req_duration{h}', 'p(95)'))} | {ms(dur(f'http_req_duration{h}', 'p(99)'))} "
        f"| {ms(dur(comb, 'p(99)'))} | {ms(dur(alt, 'p(99)'))} | {ms(dur(f'http_req_duration{h}', 'max'))} "
        f"| {100.0 * fails / max(reqs, 1):.3f} % | {dropped:.0f} | {r422 or 0:.2f} % "
        f"| {'–' if inc is None else f'{inc:.2f} %'} | {'sí' if ok else '**no**'} |")

    seen, full = val(m, f"schedules_seen{h}", "count"), val(m, f"schedules_full{h}", "count")
    full_rate = pct(val(m, f"schedule_full{h}", "passes"), val(m, f"schedule_full{h}", "fails"))
    over = pct(val(m, f"schedule_full_over_5s{h}", "passes"), val(m, f"schedule_full_over_5s{h}", "fails"))
    pr = "{phase:probe}"
    probe = pct(val(m, f"probe_offered{pr}", "passes"), val(m, f"probe_offered{pr}", "fails"))
    probes = (val(m, f"probe_offered{pr}", "passes") or 0) + (val(m, f"probe_offered{pr}", "fails") or 0)
    storm_file = out / f"{tag}_storm.txt"
    storm = storm_file.read_text().strip().splitlines()[-1] if storm_file.exists() and storm_file.read_text().strip() else "–"
    e1_rows.append(
        f"| {replicas} | {rate} | {seen or 0:.0f} | {full or 0:.0f} | {'–' if full_rate is None else f'{full_rate:.4f} %'} "
        f"| {'–' if over is None else f'{over:.4f} %'} | {ms(val(m, f'full_group_age_ms{h}', 'med'))} "
        f"| {ms(val(m, f'full_group_age_ms{h}', 'p(99)'))} | {ms(val(m, f'full_group_age_ms{h}', 'max'))} "
        f"| {probes:.0f} | {'–' if probe is None else f'{probe:.2f} %'} | {ms(val(m, f'probe_offered_age_ms{pr}', 'med'))} "
        f"| {ms(val(m, f'probe_offered_age_ms{pr}', 'p(99)'))} | {ms(val(m, f'probe_offered_age_ms{pr}', 'max'))} "
        f"| {stale if stale is None else f'{stale:.3f} %'} | `{storm}` |")

    hs = out / f"{tag}_hold_start_ms.txt"
    start = int(hs.read_text()) // 1000 if hs.exists() else 0
    cpu = cpu_in_window(out / f"{tag}_stats.txt", start, start + hold)
    engines = {n: c for n, c in cpu.items() if "engine" in n}
    mem = max((mem_max(out / f"{tag}_stats.txt", start, start + hold, n) for n in engines), default=0)
    cpu_rows.append(
        f"| {replicas} | {rate} | {sum(engines.values()) / max(len(engines), 1):.0f} % "
        f"| {cpu.get('bench-pico-lb-1', 0):.0f} % | {cpu.get('bench-pico-k6', 0):.0f} % "
        f"| {cpu.get('bench-pico-ingest-1', 0) + cpu.get('bench-pico-nats-1', 0):.0f} % | {mem:.0f} |")

    proms = sorted(out.glob(f"{tag}_prom_*.txt"))
    searches = sum(prom_sum(proms, "sched_search_duration_seconds_count", operation=op) or 0
                   for op in ("combinations", "alternatives"))
    cut = sum(prom_sum(proms, "sched_search_incomplete_total", operation=op) or 0
              for op in ("combinations", "alternatives"))
    cut_c = prom_sum(proms, "sched_search_incomplete_total", operation="combinations") or 0
    cut_a = prom_sum(proms, "sched_search_incomplete_total", operation="alternatives") or 0
    lag = max((prom_sum([p], "sched_follower_lag") or 0 for p in proms), default=0)
    prom_rows.append(
        f"| {replicas} | {rate} | {searches:.0f} | {cut:.0f} ({cut_c:.0f} / {cut_a:.0f}) "
        f"| {100.0 * cut / searches if searches else 0:.3f} % | {lag:.0f} |")

if not runs:
    sys.exit(f"sin resultados {prefix}_r*_t*.json en {out}")

print("### Latencia y errores en la meseta\n")
print("| Réplicas | Pico (sol/s) | Logradas (sol/s) | p50 (ms) | p95 (ms) | p99 (ms) | p99 /combinations | p99 /alternatives "
      "| máx (ms) | Errores | Descartadas | 422 | `complete=false` | Cumple E2 |")
print("|---|---|---|---|---|---|---|---|---|---|---|---|---|---|")
print("\n".join(rows))
print("\n### E1: combinaciones con un grupo ya lleno (se examina 1 de cada 3 respuestas de /combinations) y sonda\n")
print("| Réplicas | Pico (sol/s) | Combinaciones examinadas | Con grupo lleno | % con grupo lleno (E1 < 2 %) "
      "| % que lo ofrecen > 5 s después | Edad mediana (ms) | Edad p99 (ms) | Edad máx (ms) "
      "| Sondas | % de sondas que aún lo ofrecen | Edad mediana sonda (ms) | Edad p99 sonda (ms) | Edad máx. sonda (ms) "
      "| Respuestas `stale` | Ráfaga de cupos |")
print("|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|")
print("\n".join(e1_rows))
print("\n### Uso de recursos en la meseta (CPU sobre 100 % por CPU asignada: el motor tiene 2 CPUs = 200 %)\n")
print("| Réplicas | Pico (sol/s) | CPU por motor | CPU Traefik | CPU k6 | CPU NATS + ingesta | Memoria máx. de un motor (MiB) |")
print("|---|---|---|---|---|---|---|")
print("\n".join(cpu_rows))
print("\n### Métricas del motor (Prometheus, toda la corrida incluida la rampa)\n")
print("| Réplicas | Pico (sol/s) | Búsquedas | Cortadas por el tiempo (combinations / alternatives) | % cortadas | Retraso máx. del follower |")
print("|---|---|---|---|---|---|")
print("\n".join(prom_rows))
