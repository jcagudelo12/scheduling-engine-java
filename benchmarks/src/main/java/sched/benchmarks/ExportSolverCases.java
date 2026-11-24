package sched.benchmarks;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.Set;
import sched.domain.CourseId;
import sched.domain.Section;
import sched.domain.SectionId;
import sched.domain.TimeSlot;
import sched.domain.Weekday;
import sched.solver.BacktrackingSolver;
import sched.solver.ForwardCheckingSolver;
import sched.solver.Problem;
import sched.solver.Scored;
import sched.solver.SearchResult;
import sched.solver.SectionCost;
import sched.solver.Solver;

/**
 * Exporta a JSON problemas del solver con la salida exacta de {@link ForwardCheckingSolver} y de
 * {@link BacktrackingSolver}. La versión en Rust del solver los carga y exige resultados idénticos
 * (equivalencia entre las dos implementaciones) y mide los mismos problemas de
 * {@link SolverComparisonBenchmark}.
 *
 * <p>Regenerar (desde la raíz del repositorio; el JSON va a la ruta que se pase):
 *
 * <pre>
 * ./mvnw -q -pl benchmarks -am package -DskipTests
 * java -cp benchmarks/target/benchmarks.jar sched.benchmarks.ExportSolverCases solver-cases.json
 * cp solver-cases.json ../Scheduling_Engine/rust/solver/tests/fixtures/solver-cases.json
 * </pre>
 *
 * <p>La salida es determinista (semillas fijas): dos corridas dan el mismo archivo. Contenido:
 * <ul>
 *   <li>{@code benchmarks}: los problemas de {@code SolverComparisonBenchmark}. El costo va ya
 *       calculado por grupo, así Rust no necesita replicar {@code String.hashCode}.
 *   <li>{@code random}: {@value #RANDOM_CASES} problemas aleatorios con semilla fija (con y sin costo, k
 *       pequeños y grandes, empates de costo, cursos sin grupos o repetidos, franjas que se tocan en
 *       el borde, varios días y franjas por grupo, tiempo máximo 0 y ids no ASCII).
 * </ul>
 *
 * <p>Cada problema lleva {@code tags} con lo que realmente cubre, calculado sobre el problema ya
 * generado. Los ids de curso y de grupo se escriben siempre con escapes {@code \\uXXXX} para lo no
 * ASCII, de modo que las unidades UTF-16 que compara Java no dependan de la codificación del archivo.
 * El costo es función solo del id del grupo (dos grupos con el mismo id cuestan lo mismo).
 */
public final class ExportSolverCases {

    static final int RANDOM_CASES = 600;

    private static final long SEED = 20260101L;
    private static final long ONE_HOUR_NANOS = Duration.ofHours(1).toNanos();
    private static final int[] KS = {1, 1, 2, 3, 5, 5, 10, 40, 40, 200, 1000};
    /** Caracteres de ids no ASCII: acento, ñ, CJK, BMP alto (U+FF5E) y suplementarios (UTF-16: sustitutos). */
    private static final String[] EXOTIC = {"é", "ñ", "中", "～", "😀", "𝒜", ""};

    private ExportSolverCases() {
    }

    /** Un problema con el costo por grupo ya calculado (alineado con los candidatos). */
    private record Case(String name, List<String> tags, List<CourseId> rawCourses, Problem problem,
            boolean costed, long[] costs, Long timeLimitNanos) {
    }

    public static void main(String[] args) throws IOException {
        if (args.length != 1) {
            System.err.println("Uso: ExportSolverCases <archivo.json>");
            System.exit(2);
        }
        StringBuilder out = new StringBuilder(1 << 20);
        out.append("{\"_comment\":\"Generado por sched.benchmarks.ExportSolverCases (repositorio scheduling-engine-java); ")
                .append("no editar a mano. Regenerar: ./mvnw -q -pl benchmarks -am package -DskipTests && ")
                .append("java -cp benchmarks/target/benchmarks.jar sched.benchmarks.ExportSolverCases <salida.json>. ")
                .append("Dias: 0=lunes .. 6=domingo; franjas [dia, inicio, fin) en minutos; costo por grupo ya calculado.\",");
        out.append("\"benchmarks\":[");
        List<Case> benchmarks = benchmarkCases();
        for (int i = 0; i < benchmarks.size(); i++) {
            if (i > 0) {
                out.append(',');
            }
            write(out, benchmarks.get(i));
        }
        out.append("],\"random\":[");
        for (int i = 0; i < RANDOM_CASES; i++) {
            if (i > 0) {
                out.append(',');
            }
            write(out, randomCase(i));
        }
        out.append("]}\n");
        Files.writeString(Path.of(args[0]), out, StandardCharsets.UTF_8);
        System.out.println("Escrito " + args[0] + " (" + out.length() + " caracteres)");
    }

    // ---- Problemas de SolverComparisonBenchmark ----

    private static List<Case> benchmarkCases() {
        Map<String, Problem> problems = new LinkedHashMap<>();
        problems.put("regular6x5", SolverComparisonBenchmark.regularProblem());
        problems.put("noSolution7x8", SolverComparisonBenchmark.noSolutionProblem());
        for (int k : new int[] {5, 50, 500}) {
            problems.put("enrollment" + k, SolverComparisonBenchmark.enrollmentProblem(k, SectionCost.ZERO));
        }
        for (int k : new int[] {5, 50, 500}) {
            problems.put("enrollmentCost" + k,
                    SolverComparisonBenchmark.enrollmentProblem(k, SolverComparisonBenchmark.ENROLLMENT_COST));
        }
        List<Case> cases = new ArrayList<>();
        problems.forEach((name, p) -> {
            boolean costed = (p.cost() != SectionCost.ZERO);
            cases.add(new Case(name, List.of("benchmark"), p.courses(), p, costed, costsOf(p), null));
        });
        return cases;
    }

    private static long[] costsOf(Problem p) {
        long[] costs = new long[p.candidates().size()];
        if ((p.cost() != SectionCost.ZERO)) {
            for (int i = 0; i < costs.length; i++) {
                costs[i] = p.cost().of(p.candidates().get(i));
            }
        }
        return costs;
    }

    // ---- Problemas aleatorios ----

    private static Case randomCase(int index) {
        Random random = new Random(SEED + index);
        Set<String> tags = new LinkedHashSet<>();
        // Perfil: 0 = rejilla de horas (los bordes se tocan), 1 = minutos al azar, 2 = pocos días (muchos cruces).
        int profile = random.nextInt(3);
        boolean exotic = random.nextInt(4) == 0;
        boolean big = index % 100 == 99; // unos pocos problemas grandes
        int courseCount = random.nextInt(60) == 0 ? 0 : 1 + random.nextInt(big ? 7 : 6);
        int maxPerCourse = big ? 6 : 5;
        int days = profile == 2 ? 1 + random.nextInt(2) : 1 + random.nextInt(5);

        List<CourseId> courses = new ArrayList<>();
        List<Section> candidates = new ArrayList<>();
        for (int c = 0; c < courseCount; c++) {
            String courseName = (exotic && random.nextBoolean() ? exoticChar(random) : "") + "C" + c;
            CourseId course = new CourseId(courseName);
            courses.add(course);
            int count = random.nextInt(25) == 0 ? 0 : 1 + random.nextInt(maxPerCourse);
            if (count == 0) {
                tags.add("course_without_sections");
            }
            List<String> idsOfCourse = new ArrayList<>();
            for (int g = 0; g < count; g++) {
                String id;
                if (!idsOfCourse.isEmpty() && random.nextInt(14) == 0) {
                    id = idsOfCourse.get(random.nextInt(idsOfCourse.size()));
                    tags.add("duplicate_section_id");
                } else {
                    id = courseName + "-G" + (exotic && random.nextInt(3) > 0 ? exoticChar(random) : "") + g;
                }
                idsOfCourse.add(id);
                candidates.add(new Section(new SectionId(id), course, randomSlots(random, profile, days), 30));
            }
        }
        // Grupos de un curso que nadie pidió: el solver debe ignorarlos.
        if (random.nextInt(6) == 0) {
            candidates.add(new Section(new SectionId("OTRO-1"), new CourseId("OTRO"),
                    randomSlots(random, profile, days), 30));
            tags.add("unrequested_course");
        }
        // Los grupos de cursos distintos llegan mezclados, como en un catálogo real.
        if (random.nextBoolean()) {
            java.util.Collections.shuffle(candidates, random);
            tags.add("shuffled_candidates");
        }
        // Un curso repetido en la solicitud.
        List<CourseId> raw = new ArrayList<>(courses);
        if (!courses.isEmpty() && random.nextInt(7) == 0) {
            raw.add(random.nextInt(raw.size() + 1), courses.get(random.nextInt(courses.size())));
            tags.add("duplicate_course");
        }

        int k = random.nextInt(40) == 0 ? 0 : KS[random.nextInt(KS.length)];
        int costMode = random.nextInt(6); // 0, 1: sin costo; 2: costo cero; 3: rango 3; 4: rango 12; 5: rango 1000
        boolean costed = costMode >= 2;
        int range = switch (costMode) {
            case 3 -> 3;
            case 4 -> 12;
            case 5 -> 1000;
            default -> 1;
        };
        int salt = random.nextInt();
        SectionCost cost = section -> Math.floorMod(Objects.hash(section.id().value(), salt), range) * (costMode == 2 ? 0 : 1);

        Long limit = null;
        int limitRoll = random.nextInt(20);
        if (limitRoll == 0) {
            limit = 0L;
            tags.add("time_limit_zero");
        } else if (limitRoll == 1) {
            limit = ONE_HOUR_NANOS;
            tags.add("time_limit_generous");
        }

        Problem base = costed ? new Problem(raw, candidates, k, cost) : new Problem(raw, candidates, k);
        Problem problem = limit == null
                ? base
                : new Problem(raw, candidates, k, costed ? cost : SectionCost.ZERO, Duration.ofNanos(limit));
        tags.add(costed ? (costMode == 2 ? "cost_all_zero" : "costed") : "no_cost");
        if (exotic) {
            tags.add("non_ascii_ids");
        }
        if (k == 0) {
            tags.add("k_zero");
        } else if (k <= 3) {
            tags.add("k_small");
        } else if (k >= 200) {
            tags.add("k_large");
        }
        if (profile == 0) {
            tags.add("grid");
        }
        if (big) {
            tags.add("big");
        }
        return new Case("random-" + index, new ArrayList<>(tags), raw, problem, costed, costsOf(problem), limit);
    }

    private static String exoticChar(Random random) {
        return EXOTIC[random.nextInt(EXOTIC.length)];
    }

    private static List<TimeSlot> randomSlots(Random random, int profile, int days) {
        int count = random.nextInt(4) == 0 ? 2 + random.nextInt(2) : 1;
        List<TimeSlot> slots = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            Weekday day = Weekday.values()[random.nextInt(days)];
            int start;
            int end;
            if (profile == 0) {
                // Rejilla de horas: duraciones de 60 o 120 minutos, así que muchas franjas se tocan en el borde.
                start = (6 + random.nextInt(8)) * 60;
                end = start + 60 * (1 + random.nextInt(2));
            } else if (random.nextInt(25) == 0) {
                // Pegada a la medianoche, para probar el borde entre días.
                if (random.nextBoolean()) {
                    start = 1380;
                    end = 1440;
                } else {
                    start = 0;
                    end = 60;
                }
            } else {
                start = random.nextInt(24 * 60 - 1);
                end = start + 1 + random.nextInt(Math.min(240, 24 * 60 - start));
            }
            slots.add(TimeSlot.of(day, start, end).orElseThrow());
        }
        return slots;
    }

    // ---- Etiquetas calculadas sobre el resultado ----

    private static void derivedTags(Case c, SearchResult fc, List<String> tags) {
        Problem p = c.problem();
        if (fc.results().isEmpty() && fc.complete() && c.timeLimitNanos() == null) {
            tags.add("no_solution");
        }
        if (!fc.complete()) {
            tags.add("cut_by_time");
        }
        boolean multiDay = false;
        boolean multiSlot = false;
        boolean touches = false;
        boolean midnight = false;
        List<Section> sections = p.candidates();
        for (Section s : sections) {
            multiSlot |= s.slots().size() > 1;
            multiDay |= s.slots().stream().map(TimeSlot::day).distinct().count() > 1;
        }
        for (int i = 0; i < sections.size(); i++) {
            for (int j = 0; j < sections.size(); j++) {
                if (i == j || sections.get(i).course().equals(sections.get(j).course())) {
                    continue;
                }
                for (TimeSlot a : sections.get(i).slots()) {
                    for (TimeSlot b : sections.get(j).slots()) {
                        touches |= a.day() == b.day() && a.end() == b.start();
                        midnight |= a.end() == 1440 && b.start() == 0 && b.day().ordinal() == a.day().ordinal() + 1;
                    }
                }
            }
        }
        if (multiDay) {
            tags.add("multi_day");
        }
        if (multiSlot) {
            tags.add("multi_slot");
        }
        if (touches) {
            tags.add("edge_touch");
        }
        if (midnight) {
            tags.add("midnight_border");
        }
        // Empate de costo en el corte: la combinación k+1 de la enumeración completa cuesta igual que la k.
        if (c.costed() && p.maxResults() > 0 && c.timeLimitNanos() == null) {
            Problem all = new Problem(p.courses(), p.candidates(), Integer.MAX_VALUE, p.cost());
            List<Scored> everything = new BacktrackingSolver().search(all).results();
            if (everything.size() > p.maxResults()
                    && everything.get(p.maxResults() - 1).cost() == everything.get(p.maxResults()).cost()) {
                tags.add("tie_at_cut");
            }
        }
    }

    // ---- JSON ----

    private static void write(StringBuilder out, Case c) {
        SearchResult fc = new ForwardCheckingSolver().search(c.problem());
        SearchResult bt = new BacktrackingSolver().search(c.problem());
        List<String> tags = new ArrayList<>(c.tags());
        derivedTags(c, fc, tags);

        out.append("{\"name\":");
        string(out, c.name());
        out.append(",\"tags\":[");
        for (int i = 0; i < tags.size(); i++) {
            if (i > 0) {
                out.append(',');
            }
            string(out, tags.get(i));
        }
        out.append("],\"courses\":[");
        for (int i = 0; i < c.rawCourses().size(); i++) {
            if (i > 0) {
                out.append(',');
            }
            string(out, c.rawCourses().get(i).value());
        }
        out.append("],\"candidates\":[");
        List<Section> candidates = c.problem().candidates();
        for (int i = 0; i < candidates.size(); i++) {
            Section s = candidates.get(i);
            if (i > 0) {
                out.append(',');
            }
            out.append("{\"id\":");
            string(out, s.id().value());
            out.append(",\"course\":");
            string(out, s.course().value());
            out.append(",\"slots\":[");
            for (int j = 0; j < s.slots().size(); j++) {
                TimeSlot t = s.slots().get(j);
                if (j > 0) {
                    out.append(',');
                }
                out.append('[').append(t.day().ordinal()).append(',').append(t.start()).append(',').append(t.end()).append(']');
            }
            out.append("],\"capacity\":").append(s.capacity()).append(",\"cost\":").append(c.costs()[i]).append('}');
        }
        out.append("],\"max_results\":").append(c.problem().maxResults());
        out.append(",\"costed\":").append(c.costed());
        out.append(",\"time_limit_nanos\":").append(c.timeLimitNanos());
        out.append(",\"forward_checking\":");
        result(out, fc, true);
        out.append(",\"backtracking\":");
        result(out, bt, false);
        out.append('}');
    }

    private static void result(StringBuilder out, SearchResult r, boolean withNodes) {
        out.append("{\"complete\":").append(r.complete());
        if (withNodes) {
            out.append(",\"nodes\":").append(r.nodes());
        }
        out.append(",\"results\":[");
        for (int i = 0; i < r.results().size(); i++) {
            Scored s = r.results().get(i);
            if (i > 0) {
                out.append(',');
            }
            out.append("{\"cost\":").append(s.cost()).append(",\"ids\":[");
            for (int j = 0; j < s.schedule().sections().size(); j++) {
                if (j > 0) {
                    out.append(',');
                }
                string(out, s.schedule().sections().get(j).id().value());
            }
            out.append("]}");
        }
        out.append("]}");
    }

    /** Cadena JSON con todo lo no ASCII escapado por unidad UTF-16. */
    private static void string(StringBuilder out, String value) {
        out.append('"');
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (ch == '"' || ch == '\\') {
                out.append('\\').append(ch);
            } else if (ch < 0x20 || ch > 0x7e) {
                out.append(String.format("\\u%04x", (int) ch));
            } else {
                out.append(ch);
            }
        }
        out.append('"');
    }
}
