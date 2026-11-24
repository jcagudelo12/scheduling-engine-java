#!/usr/bin/env python3
"""Desglosa un perfil `collapsed` de async-profiler (bench/profile-server.sh) por categoría de CPU o asignación.

  profile-breakdown.py cpu   <cpu.collapsed>   <solicitudes> <cpu_ms> [--top N]
  profile-breakdown.py alloc <alloc.collapsed> <solicitudes>          [--top N]

`cpu`: reparte los µs de CPU por solicitud (cpu_ms/solicitudes, medidos con /proc/<pid>/stat y el contador de
solicitudes de Prometheus en la misma ventana) según la fracción de muestras de cada categoría.
`alloc`: reparte los bytes asignados por solicitud (el perfil se pide con --total: el valor de cada pila son bytes).

Cada muestra se asigna a UNA categoría: primero por hilo (GC, JIT, seguidor de NATS, Tomcat poller) y, en los
hilos de solicitud (hilos virtuales), caminando desde la hoja de la pila hacia la raíz hasta el primer marco que
cumpla una regla (el componente más profundo que posee el tiempo). Los marcos de JDK sin regla (java/util, String...)
se atribuyen al que los llamó. Las categorías no se solapan: suman 100 %.
"""
import re, sys
from collections import Counter

JWT_SIGN, JWT_OTHER = "JWT: firma EdDSA (Ed25519)", "JWT: decodificación y claims (base64 + JSON)"
CATS = [
    JWT_SIGN, JWT_OTHER,
    "Parseo JSON de entrada (cuerpo)", "Serialización JSON de salida",
    "Validación (RequestValidation)", "Solver (ForwardCheckingSolver, costo)",
    "Catálogo / réplica (CatalogReplica, Catalog)", "Casos de uso y dominio (GenerateCombinations, SuggestAlternatives)",
    "Controladores: mapeo DTO <-> dominio",
    "Filtro RequestIdFilter", "Filtro BodySizeLimitFilter", "Filtro JwtAuthFilter (sin verificar firma)",
    "Filtro HttpMetricsFilter (propio)", "Filtro AppSecurityHeadersFilter",
    "Filtros estándar de Spring (observación, encoding, form, contexto)",
    "Métricas Micrometer", "Logging (logback, MDC)",
    "Spring MVC (despacho, argumentos, reflexión)", "Tomcat / E/S de red (parseo HTTP, sockets, epoll)",
    "Hilos virtuales (planificador ForkJoin, park/unpark)",
    "GC", "JIT (C1/C2)", "Seguidor de NATS (CatalogFollower)", "Otros hilos de JVM / Tomcat de fondo", "Otros",
]


def frame_name(f):
    return re.sub(r"_\[[a-z0-9]\]$", "", f)


def thread_category(thread):
    if thread.startswith(("G1 ", "GC Thread", "VM Thread", "VM Periodic", "Service Thread", "Monitor Deflati")):
        return "GC"
    if "CompilerThre" in thread:
        return "JIT (C1/C2)"
    if thread.startswith("nats"):
        return "Seguidor de NATS (CatalogFollower)"
    if thread.startswith(("http-nio", "Catalina-utility")):
        return "Tomcat / E/S de red (parseo HTTP, sockets, epoll)"
    if thread.startswith("Async-profiler"):
        return "(profiler)"
    if thread.startswith("ForkJoinPool"):
        return None
    return "Otros hilos de JVM / Tomcat de fondo"


def classify_frame(f, stack):
    """Categoría de un marco, o None si no tiene regla (se atribuye al llamador)."""
    if "sched/adapters/nats" in f:
        return "Seguidor de NATS (CatalogFollower)"
    in_jwt = any("JwtVerifier" in s for s in stack)
    if in_jwt and (f.startswith(("sun/security/", "java/security/", "jdk/internal/math/")) or "JwtVerifier.signatureMatches" in f):
        return JWT_SIGN   # (sin JwtVerifier en la pila, java/security es el SecureRandom de UUID.randomUUID de RequestIdFilter)
    if f.startswith(("tools/jackson/", "com/fasterxml/jackson")):
        if in_jwt:
            return JWT_OTHER
        if any("writeWithMessageConverters" in s or "writeInternal" in s for s in stack):
            return "Serialización JSON de salida"
        return "Parseo JSON de entrada (cuerpo)"
    if "JwtVerifier" in f:
        return JWT_OTHER
    if "RequestValidation" in f:
        return "Validación (RequestValidation)"
    if f.startswith("sched/solver/"):
        return "Solver (ForwardCheckingSolver, costo)"
    if f.startswith("sched/domain/"):
        if any(s.startswith("sched/solver/") for s in stack):
            return "Solver (ForwardCheckingSolver, costo)"
        if "Catalog" in f:
            return "Catálogo / réplica (CatalogReplica, Catalog)"
        return "Casos de uso y dominio (GenerateCombinations, SuggestAlternatives)"
    if "sched/application/CatalogReplica" in f or "sched/application/Catalog" in f:
        return "Catálogo / réplica (CatalogReplica, Catalog)"
    if f.startswith("sched/application/"):
        return "Casos de uso y dominio (GenerateCombinations, SuggestAlternatives)"
    if "Controller" in f and f.startswith("sched/adapters/http/"):
        return "Controladores: mapeo DTO <-> dominio"
    if f.startswith(("io/micrometer/", "sched/server/LatencyTimer", "sched/server/Micrometer", "sched/server/ReplicaMeters")):
        return "Métricas Micrometer"
    if f.startswith(("ch/qos/logback", "org/slf4j")):
        return "Logging (logback, MDC)"
    if "RequestIdFilter" in f:
        return "Filtro RequestIdFilter"
    if "BodySizeLimitFilter" in f:
        return "Filtro BodySizeLimitFilter"
    if "JwtAuthFilter" in f:
        return "Filtro JwtAuthFilter (sin verificar firma)"
    if "HttpMetricsFilter" in f:
        return "Filtro HttpMetricsFilter (propio)"
    if "AppSecurityHeadersFilter" in f:
        return "Filtro AppSecurityHeadersFilter"
    if f.startswith(("org/springframework/web/filter/", "org/springframework/boot/web/servlet")):
        return "Filtros estándar de Spring (observación, encoding, form, contexto)"
    if f.startswith(("org/springframework/web/servlet/", "org/springframework/web/method/", "org/springframework/http/converter/",
                     "org/springframework/web/bind/", "org/springframework/core/", "org/springframework/beans/")):
        return "Spring MVC (despacho, argumentos, reflexión)"
    if f.startswith(("org/apache/", "sun/nio/", "sun/net/")):
        return "Tomcat / E/S de red (parseo HTTP, sockets, epoll)"
    if f.startswith(("java/util/concurrent/ForkJoin", "java/lang/VirtualThread", "jdk/internal/vm/Continuation", "java/lang/Thread.run")):
        return "Hilos virtuales (planificador ForkJoin, park/unpark)"
    return None


def classify(thread, frames):
    t = thread_category(thread)
    if t is not None:
        return t
    names = [frame_name(f) for f in frames]
    for i in range(len(names) - 1, -1, -1):          # de la hoja a la raíz
        c = classify_frame(names[i], names)
        if c is not None:
            return c
    return "Otros"


def read(path):
    for line in open(path, encoding="utf-8"):
        stack, n = line.rstrip("\n").rsplit(" ", 1)
        parts = stack.split(";")
        thread = parts[0].lstrip("[")
        thread = thread.rsplit(" tid=", 1)[0]
        yield thread, parts[1:], int(n)


def own_methods(path, top):
    """Métodos de sched/* (inclusive y propio) en % de las muestras de los hilos de solicitud."""
    inc, own, total = Counter(), Counter(), 0
    for thread, frames, n in read(path):
        total += n
        names = [frame_name(f) for f in frames]
        for f in set(names):
            if f.startswith("sched/"):
                inc[f] += n
        if names and names[-1].startswith("sched/"):
            own[names[-1]] += n
    print(f"\n### Top {top} métodos propios (`sched/*`), % del total de muestras\n")
    print("| Método | Inclusive | Propio (hoja) |\n|---|---:|---:|")
    for f, n in inc.most_common(top):
        print(f"| `{f.replace('/', '.')}` | {100*n/total:.1f} % | {100*own[f]/total:.1f} % |")


def cpu(path, requests, cpu_ms, top):
    us_req = cpu_ms * 1000 / requests
    by, total, leaves = Counter(), 0, {}
    for thread, frames, n in read(path):
        c = classify(thread, frames)
        if c == "(profiler)":
            continue
        by[c] += n
        total += n
        leaf = frame_name(frames[-1]) if frames else thread
        leaves.setdefault(c, Counter())[leaf] += n
    print(f"CPU del proceso: {cpu_ms} ms en {requests} solicitudes = {us_req:.1f} us/solicitud; {total} muestras\n")
    print("| Categoría | % de la CPU | us/solicitud |\n|---|---:|---:|")
    for c in CATS:
        if by[c]:
            print(f"| {c} | {100*by[c]/total:.1f} % | {us_req*by[c]/total:.1f} |")
    print(f"| **Total** | 100 % | {us_req:.1f} |")
    print("\n### Marcos hoja más frecuentes de las categorías grandes (% de la CPU total)\n")
    for c in CATS:
        if by[c] >= 0.04 * total and c in leaves:
            print(f"**{c}**: " + "; ".join(f"`{f.split('/')[-1]}` {100*n/total:.1f} %" for f, n in leaves[c].most_common(8)) + "\n")
    own_methods(path, top)


def alloc(path, requests, top):
    by, total, sites, callers = Counter(), 0, Counter(), Counter()
    for thread, frames, n in read(path):
        by[classify(thread, frames)] += n
        total += n
        names = [frame_name(f) for f in frames]
        # el último marco es la clase asignada; el anterior, el método que asigna
        if len(names) >= 2:
            sites[(names[-2], names[-1])] += n
        # se ignoran los filtros: envuelven toda la solicitud y se llevarían la asignación de Spring y Tomcat
        own = next((f for f in reversed(names) if f.startswith("sched/") and "doFilterInternal" not in f),
                   "(sin código propio en la pila: Tomcat, Spring, Jackson, filtros)")
        callers[own] += n
    print(f"Asignación: {total/1e6:.0f} MB en {requests} solicitudes = {total/requests:.0f} B/solicitud\n")
    print("| Categoría | % | B/solicitud |\n|---|---:|---:|")
    for c in CATS:
        if by[c]:
            print(f"| {c} | {100*by[c]/total:.1f} % | {by[c]/requests:.0f} |")
    print(f"\n### Top {top} sitios de asignación (método que asigna, clase asignada)\n")
    print("| Sitio | Clase | B/solicitud | % |\n|---|---|---:|---:|")
    for (m, k), n in sites.most_common(top):
        print(f"| `{m.replace('/', '.')}` | `{k.replace('/', '.')}` | {n/requests:.0f} | {100*n/total:.1f} % |")
    # Por método que asigna, sin separar clase
    meth = Counter()
    for (m, _), n in sites.items():
        meth[m] += n
    print(f"\n### Top {top} sitios de asignación agregados por método\n")
    print("| Método | B/solicitud | % |\n|---|---:|---:|")
    for m, n in meth.most_common(top):
        print(f"| `{m.replace('/', '.')}` | {n/requests:.0f} | {100*n/total:.1f} % |")
    print(f"\n### Top {top} métodos propios (`sched/*`) que originan asignaciones (el marco propio más cercano a la hoja)\n")
    print("| Método | B/solicitud | % |\n|---|---:|---:|")
    for m, n in callers.most_common(top):
        print(f"| `{m.replace('/', '.')}` | {n/requests:.0f} | {100*n/total:.1f} % |")


if __name__ == "__main__":
    args = sys.argv[1:]
    top = 15
    if "--top" in args:
        i = args.index("--top"); top = int(args[i + 1]); del args[i:i + 2]
    if args[0] == "cpu":
        cpu(args[1], int(args[2]), int(args[3]), top)
    else:
        alloc(args[1], int(args[2]), top)
