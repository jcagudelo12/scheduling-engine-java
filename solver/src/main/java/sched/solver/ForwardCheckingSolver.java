package sched.solver;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import sched.domain.CourseId;
import sched.domain.Schedule;
import sched.domain.Section;

/**
 * Búsqueda con retroceso, verificación hacia adelante (forward checking) y heurística MRV.
 *
 * <p>Variables: los cursos; dominio: los grupos candidatos del curso, como bitset. Al asignar
 * un grupo se quitan de los dominios de los demás cursos los grupos que se cruzan con él
 * (conflictos precomputados con {@link SlotBitmaps}); si algún dominio queda vacío se retrocede
 * sin explorar más. Se asigna primero el curso con menos grupos restantes (empate: orden de la
 * solicitud) y sus grupos se prueban por menor costo y luego por {@code SectionId}.
 *
 * <p>Con costo se devuelven las {@code maxResults} combinaciones de menor costo total, con
 * ramificación y poda: cota inferior = costo parcial + menor costo de cada curso sin asignar
 * dentro de su dominio actual. Sin costo la búsqueda termina en cuanto reúne {@code maxResults}.
 * La salida va por costo y luego por ids de grupo en el orden de cursos de la solicitud. Entre
 * combinaciones de igual costo que no caben en el top-K, cuáles quedan depende del orden de
 * búsqueda (determinista), no del orden por ids.
 *
 * <p>Tiempo máximo: si {@link Problem#timeLimit()} vence, la búsqueda se corta y se devuelve lo
 * encontrado con {@code complete = false}. Con costo, entonces, son las mejores combinaciones
 * halladas hasta ese momento y no un top-K garantizado. El reloj se lee cada
 * {@value Budget#CHECK_EVERY} nodos, así que el corte llega con una demora de microsegundos.
 */
public final class ForwardCheckingSolver implements Solver {

    /** Contadores de la última búsqueda; solo para pruebas. */
    static final class Stats {
        long nodes;
    }

    @Override
    public SearchResult search(Problem problem) {
        return search(problem, new Stats());
    }

    SearchResult search(Problem problem, Stats stats) {
        int n = problem.courses().size();
        if (problem.maxResults() <= 0) {
            return new SearchResult(List.of(), true);
        }
        Map<CourseId, Integer> courseIndex = new HashMap<>();
        for (int i = 0; i < n; i++) {
            courseIndex.put(problem.courses().get(i), i);
        }
        List<Section> sections = new ArrayList<>();
        List<Integer> courseOfList = new ArrayList<>();
        for (Section s : problem.candidates()) {
            Integer c = courseIndex.get(s.course());
            if (c != null) {
                sections.add(s);
                courseOfList.add(c);
            }
        }
        int[] courseOf = courseOfList.stream().mapToInt(Integer::intValue).toArray();
        for (int c = 0; c < n; c++) {
            final int course = c;
            if (Arrays.stream(courseOf).noneMatch(x -> x == course)) {
                // Un curso sin grupos hace imposible cualquier combinación: la respuesta es exacta.
                return new SearchResult(List.of(), true);
            }
        }
        return new Search(problem, sections.toArray(new Section[0]), courseOf, stats, Budget.of(problem)).run();
    }

    private static final class Entry {
        final long cost;
        final int[] chosen;

        Entry(long cost, int[] chosen) {
            this.cost = cost;
            this.chosen = chosen;
        }
    }

    private static final class Search {
        private final int n;
        private final int words;
        private final int k;
        private final boolean costed;
        private final Section[] sections;
        private final long[] costs;
        private final long[][] conflicts;
        private final int[][] order;
        private final long[][][] domains;
        private final int[] chosen;
        private final boolean[] assigned;
        private final Stats stats;
        private final Budget budget;
        private final Comparator<Entry> output;
        private final PriorityQueue<Entry> top;

        Search(Problem problem, Section[] sections, int[] courseOf, Stats stats, Budget budget) {
            this.n = problem.courses().size();
            this.k = problem.maxResults();
            this.costed = problem.hasCost();
            this.sections = sections;
            this.stats = stats;
            this.budget = budget;
            int m = sections.length;
            this.words = Math.max((m + 63) >>> 6, 1);

            costs = new long[m];
            for (int g = 0; g < m; g++) {
                costs[g] = problem.cost().of(sections[g]);
                if (costs[g] < 0) {
                    throw new IllegalArgumentException("El costo de un grupo no puede ser negativo: " + sections[g].id());
                }
            }

            SlotBitmaps bitmaps = SlotBitmaps.of(Arrays.asList(sections));
            conflicts = new long[m][words];
            for (int a = 0; a < m; a++) {
                for (int b = a + 1; b < m; b++) {
                    if (courseOf[a] != courseOf[b] && bitmaps.clash(a, b)) {
                        conflicts[a][b >>> 6] |= 1L << b;
                        conflicts[b][a >>> 6] |= 1L << a;
                    }
                }
            }

            // Grupos de cada curso por menor costo y luego SectionId.
            List<List<Integer>> perCourse = new ArrayList<>();
            for (int c = 0; c < n; c++) {
                perCourse.add(new ArrayList<>());
            }
            for (int g = 0; g < m; g++) {
                perCourse.get(courseOf[g]).add(g);
            }
            Comparator<Integer> byCost = Comparator.<Integer>comparingLong(g -> costs[g])
                    .thenComparing(g -> sections[g].id());
            order = new int[n][];
            for (int c = 0; c < n; c++) {
                perCourse.get(c).sort(byCost);
                order[c] = perCourse.get(c).stream().mapToInt(Integer::intValue).toArray();
            }

            domains = new long[n + 1][n][words];
            for (int g = 0; g < m; g++) {
                domains[0][courseOf[g]][g >>> 6] |= 1L << g;
            }
            chosen = new int[n];
            assigned = new boolean[n];

            output = Comparator.comparingLong((Entry e) -> e.cost).thenComparing(this::compareIds);
            top = new PriorityQueue<>(output.reversed());
        }

        private int compareIds(Entry a, Entry b) {
            for (int c = 0; c < n; c++) {
                int cmp = sections[a.chosen[c]].id().compareTo(sections[b.chosen[c]].id());
                if (cmp != 0) {
                    return cmp;
                }
            }
            return 0;
        }

        SearchResult run() {
            search(0, 0);
            List<Entry> entries = new ArrayList<>(top);
            entries.sort(output);
            List<Scored> result = new ArrayList<>(entries.size());
            for (Entry e : entries) {
                List<Section> picked = new ArrayList<>(n);
                for (int c = 0; c < n; c++) {
                    picked.add(sections[e.chosen[c]]);
                }
                result.add(new Scored(new Schedule(picked), e.cost));
            }
            return new SearchResult(result, !budget.cut(), stats.nodes);
        }

        private boolean full() {
            return top.size() >= k;
        }

        private void search(int level, long cost) {
            if (budget.expired()) {
                return;
            }
            stats.nodes++;
            if (level == n) {
                record(cost);
                return;
            }
            // MRV y cota inferior en una sola pasada sobre los cursos sin asignar.
            int v = -1;
            int bestCard = Integer.MAX_VALUE;
            long sumMin = 0;
            for (int c = 0; c < n; c++) {
                if (assigned[c]) {
                    continue;
                }
                long[] d = domains[level][c];
                int card = 0;
                for (long w : d) {
                    card += Long.bitCount(w);
                }
                if (card < bestCard) {
                    bestCard = card;
                    v = c;
                }
                if (costed) {
                    sumMin += costs[firstIn(d, order[c])];
                }
            }
            long othersMin = costed ? sumMin - costs[firstIn(domains[level][v], order[v])] : 0;

            long[] dv = domains[level][v];
            for (int g : order[v]) {
                if ((dv[g >>> 6] & (1L << g)) == 0) {
                    continue;
                }
                long next = cost + costs[g];
                // Los grupos vienen por costo creciente: si este ya no mejora, ninguno siguiente lo hará.
                if (full() && next + othersMin >= top.peek().cost) {
                    break;
                }
                if (!filter(level, v, g)) {
                    continue;
                }
                assigned[v] = true;
                chosen[v] = g;
                search(level + 1, next);
                assigned[v] = false;
                if (budget.expired()) {
                    return;
                }
            }
        }

        private static int firstIn(long[] domain, int[] order) {
            for (int g : order) {
                if ((domain[g >>> 6] & (1L << g)) != 0) {
                    return g;
                }
            }
            throw new IllegalStateException("dominio vacío");
        }

        /** Forward checking: dominios del siguiente nivel sin los grupos que chocan con {@code g}. */
        private boolean filter(int level, int v, int g) {
            long[] conf = conflicts[g];
            for (int c = 0; c < n; c++) {
                if (assigned[c] || c == v) {
                    continue;
                }
                long[] from = domains[level][c];
                long[] to = domains[level + 1][c];
                long any = 0;
                for (int w = 0; w < words; w++) {
                    long x = from[w] & ~conf[w];
                    to[w] = x;
                    any |= x;
                }
                if (any == 0) {
                    return false;
                }
            }
            return true;
        }

        private void record(long cost) {
            if (!full()) {
                top.add(new Entry(cost, chosen.clone()));
            } else if (cost < top.peek().cost) {
                top.poll();
                top.add(new Entry(cost, chosen.clone()));
            }
        }
    }
}
