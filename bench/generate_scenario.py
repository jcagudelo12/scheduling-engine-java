#!/usr/bin/env python3
"""Genera los datos de la prueba de carga con semillas fijas, para que todas las corridas
reciban exactamente el mismo catálogo y los mismos estudiantes en el mismo orden.

Perfiles (`--profile`):

- `uniform` (por defecto, el de bench/run.sh): catálogo uniforme y estudiantes con 6 cursos al azar.
  - bench/scenario.json: catálogo del tamaño de una universidad (formato de `fake-institution`).
  - bench/students.json: estudiantes con 6 cursos cada uno, que k6 recorre en orden.
- `itm`: un pico de matrícula parecido al del ITM (docs/calidad/02-supuestos.md, S5 y S7): oferta
  por programa y semestre (las asignaturas de un semestre comparten estudiantes), 2 a 6 grupos por
  asignatura (8 en las más demandadas), franjas de 2 h entre 06:00 y 22:00 de lunes a sábado, y
  estudiantes con 4 o 5 asignaturas en el 70 % de los casos. Además de los dos archivos anteriores
  escribe:
  - bench/alternatives.json: solicitudes de `/alternatives` (selección con un grupo lleno, o que
    se llena en la ráfaga de cupos, y `enrolled`).
  - bench/worst-hot.json: perfiles 7x8 con las asignaturas más demandadas y un `avoid` muy amplio.
  - bench/worst.json: peor caso sintético difícil: 7 asignaturas "W" de 8 grupos que casi no caben
    en el horario (el solver recorre gran parte del árbol); se agregan al catálogo, sin cambiar el resto.
  - bench/drain.txt: grupos populares, en el orden en que `seat-storm` los vacía.

Uso: python3 bench/generate_scenario.py [cursos] [estudiantes] [--profile itm] [--seed n]
(en `itm`, `cursos` se ignora: la cantidad sale de la estructura de programas).
"""

import argparse
import json
import random
from collections import Counter
from pathlib import Path

OUT = Path(__file__).parent
DAYS = ["monday", "tuesday", "wednesday", "thursday", "friday", "saturday"]
STARTS = [6 * 60 + 120 * i for i in range(8)]  # bloques de 2 h entre 06:00 y 22:00


def hhmm(minutes):
    return f"{minutes // 60:02d}:{minutes % 60:02d}"


def write(name, data):
    (OUT / name).write_text(json.dumps(data))


# ---------------------------------------------------------------------------------------------
# Perfil uniforme (el original de bench/run.sh; no cambiar el orden de uso de los generadores)
# ---------------------------------------------------------------------------------------------

def uniform(courses_n, students_n, max_credits):
    sections_per_course = 5
    courses_per_student = 6
    credits_options = [2, 3, 4]
    catalog_rng = random.Random(42)
    # Generador aparte para no alterar los grupos generados antes de que existieran los créditos.
    credits_rng = random.Random(99)
    courses = [{"id": f"C{c:04d}", "credits": credits_rng.choice(credits_options)} for c in range(courses_n)]
    sections = []
    for c in range(courses_n):
        for s in range(sections_per_course):
            days = catalog_rng.sample(DAYS, 2)  # dos sesiones semanales
            start = catalog_rng.choice(STARTS)
            capacity = catalog_rng.choice([25, 30, 35, 40])
            sections.append({
                "id": f"C{c:04d}-{s:02d}",
                "course": f"C{c:04d}",
                "capacity": capacity,
                # Alrededor del 10 % de los grupos ya están llenos.
                "available": 0 if catalog_rng.random() < 0.1 else catalog_rng.randint(1, capacity),
                "slots": [{"day": d, "start": hhmm(start), "end": hhmm(start + 120)} for d in days],
            })

    students_rng = random.Random(7)
    students = [
        {
            "student_id": f"est-{i:05d}",
            "eligible_courses": [f"C{c:04d}" for c in students_rng.sample(range(courses_n), courses_per_student)],
        }
        for i in range(students_n)
    ]
    write("scenario.json", {"catalog_seq": 1, "max_credits": max_credits, "courses": courses, "students": [],
                            "sections": sections})
    write("students.json", students)
    print(f"{len(sections)} grupos de {courses_n} cursos; {len(students)} estudiantes")


# ---------------------------------------------------------------------------------------------
# Perfil ITM
# ---------------------------------------------------------------------------------------------

PROGRAMS = 24
SEMESTERS = 9
SERVICE_PER_SEMESTER = 14           # asignaturas de servicio (cálculo, física...) compartidas entre programas
SERVICE_SEMESTERS = 4               # solo en los primeros semestres
# Distribución de asignaturas por estudiante: 1–3 → 7 %, 4–5 → 70 %, 6–7 → 18,5 %, 8 → 4,5 %.
COURSE_COUNT = [(1, 0.023), (2, 0.023), (3, 0.024), (4, 0.28), (5, 0.42), (6, 0.12), (7, 0.065), (8, 0.045)]
START_WEIGHTS = [0.10, 0.14, 0.14, 0.10, 0.12, 0.14, 0.14, 0.12]   # 06:00 … 20:00
CAPACITY = [30, 35, 40, 45]
# Preferencias de jornada: ventanas (día, inicio, fin) en minutos, por patrón.
AVOID_PATTERNS = {
    "madrugada": [(d, 360, 480) for d in DAYS],
    "noche": [(d, 1080, 1320) for d in DAYS[:5]],
    "sabado": [("saturday", 360, 1320)],
    "manana": [(d, 360, 720) for d in DAYS[:5]],
    "tarde": [(d, 840, 1080) for d in DAYS[:5]],
}
ADVERSARIAL_CELLS = 14   # celdas del tablero del peor caso difícil
HOT_COURSES = 35        # las más demandadas llevan 8 grupos (peor caso 7 × 8, S7)
DRAIN_SECTIONS = 1500


def weighted(rng, options):
    r, acc = rng.random(), 0.0
    for value, weight in options:
        acc += weight
        if r < acc:
            return value
    return options[-1][0]


def itm(students_n, max_credits, seed, worst_share, avoid_share, regular_share):
    rng = random.Random(seed)
    courses = {}                    # id → créditos
    plan = {}                       # (programa, semestre) → asignaturas del plan
    next_id = 0

    def new_course():
        nonlocal next_id
        cid = f"C{next_id:04d}"
        next_id += 1
        courses[cid] = rng.choices([2, 3, 4], [0.30, 0.45, 0.25])[0]
        return cid

    service = {s: [new_course() for _ in range(SERVICE_PER_SEMESTER)] for s in range(1, SERVICE_SEMESTERS + 1)}
    for p in range(PROGRAMS):
        for s in range(1, SEMESTERS + 1):
            size = rng.choice([5, 5, 6])
            shared = rng.sample(service[s], 2) if s in service else []
            plan[(p, s)] = shared + [new_course() for _ in range(size - len(shared))]

    # Estudiantes: programa y semestre (más estudiantes en los primeros semestres).
    cohorts = [(p, s) for p in range(PROGRAMS) for s in range(1, SEMESTERS + 1)]
    program_weight = [rng.uniform(0.5, 1.5) for _ in range(PROGRAMS)]
    cohort_weight = [program_weight[p] * (1.0 - 0.07 * (s - 1)) for p, s in cohorts]
    demand = Counter()
    students = []
    shares_4plus = 0
    for i in range(students_n):
        p, s = rng.choices(cohorts, cohort_weight)[0]
        n = weighted(rng, COURSE_COUNT)
        own = plan[(p, s)]
        if rng.random() < regular_share:
            chosen = rng.sample(own, min(n, len(own)))
            extra = n - len(chosen)
            if extra > 0:  # materias repetidas de semestres anteriores
                pool = [c for t in range(max(1, s - 2), s) for c in plan[(p, t)]] or \
                       [c for t in range(s + 1, min(SEMESTERS, s + 2) + 1) for c in plan[(p, t)]]
                chosen += rng.sample(pool, min(extra, len(pool)))
        else:  # irregular: mezcla semestres cercanos de su programa
            pool = [c for t in range(max(1, s - 2), min(SEMESTERS, s + 2) + 1) for c in plan[(p, t)]]
            chosen = rng.sample(pool, min(n, len(pool)))
        if len(set(chosen) & set(own)) >= 4:
            shares_4plus += 1
        demand.update(chosen)
        students.append({"student_id": f"est-{i:05d}", "eligible_courses": sorted(chosen)})
    rng.shuffle(students)  # llegada: no por cohorte

    # Grupos: 2–6 según la demanda; los HOT_COURSES más demandados, 8.
    ranked = [c for c, _ in demand.most_common()]
    hot = set(ranked[:HOT_COURSES])
    sections, by_course = [], {}
    for c in courses:
        if c in hot:
            groups = 8
        else:
            groups = max(2, min(6, round(demand[c] / 38)))
        credits = courses[c]
        by_course[c] = []
        for g in range(groups):
            sessions = 1 if credits == 2 else 2
            start = rng.choices(STARTS, START_WEIGHTS)[0]
            capacity = rng.choice(CAPACITY)
            by_course[c].append({
                "id": f"{c}-{g:02d}",
                "course": c,
                "capacity": capacity,
                "available": 0 if rng.random() < 0.10 else rng.randint(1, capacity),
                "slots": [{"day": d, "start": hhmm(start), "end": hhmm(start + 120)}
                          for d in rng.sample(DAYS, sessions)],
            })
        sections.extend(by_course[c])
    full = {s["id"] for s in sections if s["available"] == 0}

    def avoid_for(student_index):
        windows = []
        for name in rng.sample(sorted(AVOID_PATTERNS), rng.choice([1, 1, 2])):
            windows += [{"day": d, "start_minute": a, "end_minute": b} for d, a, b in AVOID_PATTERNS[name]]
        return windows

    # Mezcla de /combinations: ~35 % con avoid y una fracción de perfiles peor caso 7 × 8.
    hot_list = sorted(hot)
    worst_n = int(students_n * worst_share)

    def worst(i):
        return {"student_id": f"wc-{i:05d}", "eligible_courses": sorted(rng.sample(hot_list, 7)),
                "avoid": avoid_for(i)}

    mix = []
    for i, st in enumerate(students):
        body = dict(st)
        if rng.random() < avoid_share:
            body["avoid"] = avoid_for(i)
        mix.append(body)
    for j in range(worst_n):  # reemplaza a estudiantes repartidos de forma pareja
        mix[(j * students_n) // max(1, worst_n)] = worst(j)
    # worst.json: los mismos perfiles 7x8, pero con un `avoid` tan amplio (mañanas, noches y sábado) que casi
    # ninguna combinación llega a penalización 0 y la poda por costo casi no corta el árbol.
    broad = []
    for name in ("manana", "noche", "sabado"):
        broad += [{"day": d, "start_minute": a, "end_minute": b} for d, a, b in AVOID_PATTERNS[name]]
    worst_cases = [dict(worst(i), avoid=broad) for i in range(2000)]

    # Ráfaga de cupos: grupos con cupo de las asignaturas más demandadas, intercaladas.
    drain, per_course = [], {}
    for c in ranked:
        open_sections = [s["id"] for s in by_course[c] if s["id"] not in full]
        rng.shuffle(open_sections)
        per_course[c] = open_sections
    for round_ in range(8):
        for c in ranked:
            if round_ < len(per_course[c]):
                drain.append(per_course[c][round_])
    drain = drain[:DRAIN_SECTIONS]
    drain_set = set(drain)

    # /alternatives: selección con un grupo lleno (o que se llenará) y alguno ya inscrito.
    alternatives = []
    for st in students:
        if len(alternatives) >= 5000:
            break
        selection = {c: rng.choice(by_course[c])["id"] for c in st["eligible_courses"]}
        flagged = [c for c, g in selection.items() if g in full or g in drain_set]
        if not flagged:
            candidates = [c for c in st["eligible_courses"] if any(s["id"] in full or s["id"] in drain_set
                                                                   for s in by_course[c])]
            if not candidates:
                continue
            c = rng.choice(candidates)
            selection[c] = rng.choice([s["id"] for s in by_course[c] if s["id"] in full or s["id"] in drain_set])
            flagged = [c]
        others = [g for c, g in selection.items() if c not in flagged and g not in full]
        enrolled = rng.sample(others, min(len(others), rng.choice([1, 1, 2]))) if others else []
        body = {"student_id": st["student_id"], "eligible_courses": st["eligible_courses"],
                "selection": selection, "enrolled": enrolled}
        if rng.random() < avoid_share:
            body["avoid"] = avoid_for(0)
        alternatives.append(body)

    # Peor caso difícil de verdad: 7 asignaturas "W" de 8 grupos con dos sesiones cada una, todas en un
    # tablero de pocas celdas (día, bloque). Hay 14 sesiones por combinación y solo `cells` celdas libres,
    # así que casi ninguna combinación cabe: el solver recorre gran parte del árbol (generador aparte,
    # para no alterar el resto de los datos).
    write("worst-hot.json", worst_cases)
    wrng = random.Random(seed + 1)
    board = [(d, STARTS[b]) for b in range(4) for d in DAYS[:4]]   # 16 celdas: lunes a jueves, 06:00 a 14:00
    board = board[:ADVERSARIAL_CELLS]
    w_courses = []
    for w in range(7):
        cid = f"W{w:03d}"
        courses[cid] = 3
        w_courses.append(cid)
        for g in range(8):
            cells = wrng.sample(board, 2)
            sections.append({
                "id": f"{cid}-{g:02d}", "course": cid, "capacity": 40, "available": 20,
                "slots": [{"day": d, "start": hhmm(st), "end": hhmm(st + 120)} for d, st in cells],
            })
    worst_cases = [{"student_id": f"wc-{i:05d}", "eligible_courses": w_courses, "avoid": broad}
                   for i in range(2000)]

    catalog = {"catalog_seq": 1, "max_credits": max_credits,
               "courses": [{"id": c, "credits": cr} for c, cr in courses.items()],
               "students": [], "sections": sections}
    write("scenario.json", catalog)
    write("students.json", mix)
    write("alternatives.json", alternatives)
    write("worst.json", worst_cases)
    (OUT / "drain.txt").write_text("\n".join(drain) + "\n")

    counts = Counter(len(s["eligible_courses"]) for s in students)
    groups = Counter(len(v) for v in by_course.values())
    total_credits_over = sum(1 for s in students if sum(courses[c] for c in s["eligible_courses"]) > max_credits)
    print(f"{len(courses)} asignaturas en {PROGRAMS} programas x {SEMESTERS} semestres; {len(sections)} grupos "
          f"({sum(1 for s in sections if s['id'] in full) / len(sections):.1%} llenos al inicio)")
    print("grupos por asignatura: " + ", ".join(f"{k}: {v}" for k, v in sorted(groups.items())))
    print(f"{students_n} estudiantes; asignaturas por estudiante: "
          + ", ".join(f"{k}: {counts[k] / students_n:.1%}" for k in sorted(counts)))
    print(f"S5: {shares_4plus / students_n:.1%} de los estudiantes comparten 4+ materias con su cohorte "
          f"(programa y semestre)")
    print(f"/combinations: {len(mix)} solicitudes, {sum(1 for m in mix if 'avoid' in m) / len(mix):.1%} con avoid, "
          f"{worst_n} de peor caso 7x8; /alternatives: {len(alternatives)}; peor caso: {len(worst_cases)}; "
          f"grupos a vaciar: {len(drain)}")
    print(f"estudiantes sobre el tope de {max_credits} créditos (422): {total_credits_over / students_n:.2%}")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("courses", nargs="?", type=int, default=None)
    parser.add_argument("students", nargs="?", type=int, default=None)
    parser.add_argument("--profile", choices=["uniform", "itm"], default="uniform")
    parser.add_argument("--seed", type=int, default=None, help="semilla del perfil itm (por defecto 2026)")
    parser.add_argument("--max-credits", type=int, default=None)
    parser.add_argument("--worst-share", type=float, default=0.005, help="itm: fracción de peor caso en students.json")
    parser.add_argument("--avoid-share", type=float, default=0.35, help="itm: fracción de solicitudes con avoid")
    parser.add_argument("--regular-share", type=float, default=0.74,
                        help="itm: fracción de estudiantes que cursan lo de su semestre (S5)")
    args = parser.parse_args()
    if args.profile == "itm":
        # Tope alto a propósito: la prueba mide la búsqueda; solo ~1 % de los perfiles lo supera (422).
        itm(args.students or 29_755, args.max_credits or 28, args.seed if args.seed is not None else 2026,
            args.worst_share, args.avoid_share, args.regular_share)
    else:
        # Tope del periodo: 6 cursos de 4 créditos suman 24, así que ningún estudiante generado lo
        # supera y la prueba de carga mide la búsqueda, no el rechazo por créditos.
        uniform(args.courses if args.courses is not None else 1000,
                args.students if args.students is not None else 50_000, args.max_credits or 24)
