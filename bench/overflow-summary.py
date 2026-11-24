#!/usr/bin/env python3
"""Resume una corrida de bench/overflow-run.sh: CPU por grupo de hilos, CPU ajena en los núcleos del motor, CPU de k6,
latencia de k6 (meseta medida y calentamiento), VUs, iteraciones descartadas y latencia del lado del servidor.

  overflow-summary.py WORK ENGINE_CPUS K6_CPUS RATE     # escribe WORK/summary.json y lo imprime en una línea
"""
import json, re, sys
from collections import defaultdict
from pathlib import Path

HZ = 100.0
work = Path(sys.argv[1])
engine_cpus = sys.argv[2]
k6_cpus = sys.argv[3] if len(sys.argv) > 3 else ""
rate = int(sys.argv[4]) if len(sys.argv) > 4 else 0


def cpuset(spec):
    out = []
    for part in spec.split(","):
        if "-" in part:
            a, b = part.split("-")
            out += range(int(a), int(b) + 1)
        elif part:
            out.append(int(part))
    return out


def threads(path):
    res = {}
    for line in Path(path).read_text().splitlines():
        i, j = line.index("("), line.rindex(")")
        tid = int(line[:i])
        rest = line[j + 2:].split()
        res[tid] = (line[i + 1:j], int(rest[11]) + int(rest[12]))  # utime + stime
    return res


def group(name):
    if name.startswith("ForkJoinPool-1-"): return "portadores de hilos virtuales (ForkJoinPool-1)"
    if "Poller" in name: return "Tomcat poller"
    if "Acceptor" in name: return "Tomcat acceptor"
    if name.startswith(("C1 Comp", "C2 Comp")) or "Compile" in name: return "JIT"
    if name.startswith("JFR"): return "JFR"
    if name.startswith(("G1 ", "GC Thread", "ZGC", "ZWorker", "ZDirector", "ZDriver", "Serial")) or name == "VM Thread": return "GC / VM"
    if name.startswith("sched") or "nats" in name.lower() or "catalog" in name.lower(): return "seguidor NATS / catálogo"
    if name.startswith(("http-nio", "tomcat", "exec", "Catalina")): return "hilos de plataforma de Tomcat (solicitud)"
    return "otros"


def stat(path):
    res = {}
    for line in Path(path).read_text().splitlines():
        f = line.split()
        if f[0] == "cpu": continue
        v = list(map(int, f[1:]))
        res[int(f[0][3:])] = (sum(v[:3]) + sum(v[5:8]), sum(v[:8]))  # busy (user,nice,system,irq,softirq,steal), total
    return res


t0 = int(Path(work, "t0.txt").read_text()); t1 = int(Path(work, "t1.txt").read_text())
wall = (t1 - t0) / 1000.0
a, b = threads(work / "threads0.txt"), threads(work / "threads1.txt")
groups = defaultdict(float)
workers = {}
for tid, (name, ticks) in b.items():
    d = (ticks - a.get(tid, (name, 0))[1]) / HZ
    groups[group(name)] += d
    if name.startswith("ForkJoinPool-1-"): workers[str(tid)] = round(d / wall, 3)
total_cpu = sum(groups.values())
ecpus = cpuset(engine_cpus)
s0, s1 = stat(work / "stat0.txt"), stat(work / "stat1.txt")
busy = {c: round((s1[c][0] - s0[c][0]) / (s1[c][1] - s0[c][1]), 3) for c in ecpus}
busy_s = sum(s1[c][0] - s0[c][0] for c in ecpus) / HZ
foreign = max(0.0, busy_s - total_cpu)  # CPU ajena (otros procesos) consumida en los núcleos del motor

k6 = 0.0
for p in (0, 1):
    pass
def k6ticks(path):
    tot = 0
    for line in Path(path).read_text().splitlines():
        j = line.rindex(")"); f = line[j + 2:].split(); tot += int(f[11]) + int(f[12])
    return tot
try:
    k6 = (k6ticks(work / "k6proc1.txt") - k6ticks(work / "k6proc0.txt")) / HZ / wall
except Exception:
    k6 = float("nan")


def prom(path):
    cnt = defaultdict(float); buckets = defaultdict(float)
    for line in Path(path).read_text().splitlines():
        m = re.match(r'sched_http_latency_seconds_(count|bucket)\{.*endpoint="(\w+)",status="(\d+)"(?:,le="([^"]+)")?\} ([\d.eE+]+)', line)
        if not m: continue
        kind, ep, st, le, v = m.groups()
        if st not in ("200", "422"): continue
        if kind == "count": cnt[ep] += float(v)
        else: buckets[le] += float(v)
    return cnt, buckets
have_prom = (work / "prom0.txt").exists() and (work / "prom1.txt").exists()
if have_prom:
    c0, bk0 = prom(work / "prom0.txt"); c1, bk1 = prom(work / "prom1.txt")
else:   # sin lecturas de Prometheus durante la ventana (SNAP=noprom): las solicitudes salen de k6
    c0 = c1 = defaultdict(float); bk0 = bk1 = defaultdict(float)
reqs_ok = sum(c1.values()) - sum(c0.values())
def count_all(path):
    return sum(float(l.split()[-1]) for l in Path(path).read_text().splitlines() if l.startswith("sched_http_latency_seconds_count"))
reqs_all = count_all(work / "prom1.txt") - count_all(work / "prom0.txt") if have_prom else None
les = sorted((k for k in bk1 if k != "+Inf"), key=float)
dtot = sum(c1.values()) - sum(c0.values())
def over(le):  # fracción de solicitudes 200/422 del motor que tardaron más de `le` s en el filtro
    return 1 - (bk1[le] - bk0[le]) / dtot if dtot else float("nan")
srv_over = {le: round(over(le), 4) for le in ("0.01", "0.1") if le in bk1} if have_prom else None
gc = {}
for lbl, path in (("1", "prom1.txt"), ("0", "prom0.txt")):
    n = s = 0.0
    if not have_prom: break
    for line in Path(work, path).read_text().splitlines():
        if line.startswith("jvm_gc_pause_seconds_count"): n += float(line.split()[-1])
        if line.startswith("jvm_gc_pause_seconds_sum"): s += float(line.split()[-1])
    gc[lbl] = (n, s)

m = json.loads(Path(work, "k6.json").read_text())["metrics"]
def val(k):
    v = m.get(k, {}); return v.get("values", v)
hold = val("http_req_duration{phase:hold}"); warm = val("http_req_duration{phase:warm}")
hold_reqs = val("http_reqs{phase:hold}").get("count", 0)
if reqs_all is None:   # k6 en la meseta medida + ~100/s de la sonda de E1 (que no usa token y no entra en phase:hold)
    reqs_all = hold_reqs + (100 * wall if rate and Path(work, "storm.txt").exists() else 0)
out = {
    "rate": rate, "wall_s": round(wall, 1),
    "req_per_s": round(reqs_all / wall, 1),
    "engine_cpu_cores": round(total_cpu / wall, 3),
    "engine_cpu_pct_of_set": round(100 * total_cpu / wall / len(ecpus), 1),
    "cpu_us_per_req": round(1e6 * total_cpu / reqs_all, 1) if reqs_all else None,
    "cpu_by_group_cores": {k: round(v / wall, 3) for k, v in sorted(groups.items(), key=lambda kv: -kv[1])},
    "carrier_cores": workers,
    "busy_per_engine_cpu": busy, "foreign_cpu_cores_on_engine_cpus": round(foreign / wall, 3),
    "k6_cpu_cores": round(k6, 3),
    "p50_ms": round(hold.get("med", float("nan")), 2), "p95_ms": round(hold.get("p(95)", float("nan")), 1),
    "p99_ms": round(hold.get("p(99)", float("nan")), 1), "max_ms": round(hold.get("max", float("nan")), 0),
    "warm_p99_ms": round(warm.get("p(99)", float("nan")), 1) if warm else None,
    "hold_req_per_s": round(hold_reqs / max(1.0, wall), 1),
    "dropped_iterations": val("dropped_iterations").get("count", 0),
    "vus_max": val("vus_max").get("max", val("vus_max").get("value")),
    "vus_cap": min(10000, max(500, rate * 2)), "vus_pre": max(100, round(rate / 10)),
    "server_filter_frac_over": srv_over,
    "gc_pauses_in_window": [gc["1"][0] - gc["0"][0], round(gc["1"][1] - gc["0"][1], 3)] if have_prom else None,
}
slices = {}
for k in m:
    mm = re.match(r"http_req_duration\{slice:(\d+)\}$", k)
    if mm:
        v = val(k)
        slices[int(mm.group(1))] = [round(v.get("med", 0), 1), round(v.get("p(95)", 0), 1), round(v.get("p(99)", 0), 1), round(v.get("max", 0))]
if slices:
    out["tramos_p50_p95_p99_max_ms"] = {str(k): slices[k] for k in sorted(slices)}
if slices:
    # Recuperación: inicio del primer tramo de 10 s desde el cual todos los tramos completos siguientes tienen p95 < 20 ms
    # (el último tramo y el del descenso se ignoran); p99 estable: el peor p99 de tramo desde entonces.
    ks = sorted(k for k in slices if slices[k][3] > 0)[:-1]
    rec = None
    for i, k in enumerate(ks):
        if all(slices[j][1] < 20 for j in ks[i:]):
            rec = k; break
    out["recovery_s"] = None if rec is None else rec * 10   # supone SLICE_S=10
    out["steady_worst_p99_ms"] = max((slices[j][2] for j in ks if rec is not None and j >= rec), default=None)
(work / "summary.json").write_text(json.dumps(out, ensure_ascii=False, indent=1))
print(json.dumps(out, ensure_ascii=False))
