package sched.simulation.experiment;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SplittableRandom;
import sched.domain.CourseId;
import sched.domain.EnrollmentRules;
import sched.domain.Section;
import sched.domain.SectionId;
import sched.domain.SectionSeats;
import sched.domain.TimeSlot;
import sched.domain.Weekday;

/**
 * Oferta sintética estilo ITM. Supuestos (documentados en docs/simulador.md):
 * bloques de 2 h entre 6:00 y 22:00 de lunes a sábado; de 1 a 3 bloques por semana según los
 * créditos (2 a 4); de 2 a 6 grupos por asignatura según su demanda; cupo total de la asignatura
 * = demanda × holgura, repartido desigual y con menos cupo en la jornada más pedida.
 */
final class OfferGenerator {

    private OfferGenerator() {
    }

    /** Plan de estudios: asignaturas comunes en los primeros semestres y propias de cada programa. */
    static Curriculum curriculum(ExperimentConfig.OfferParams p, List<String> programs, SplittableRandom rng) {
        Map<CourseId, Integer> credits = new LinkedHashMap<>();
        Map<String, Map<Integer, List<CourseId>>> pools = new LinkedHashMap<>();
        for (int semester = 1; semester <= Curriculum.SEMESTERS; semester++) {
            List<CourseId> shared = new ArrayList<>();
            if (semester <= p.sharedUpToSemester()) {
                for (int k = 1; k <= p.sharedCoursesPerSemester(); k++) {
                    shared.add(newCourse(credits, String.format("CB%02d-%d", semester, k), p, rng));
                }
            }
            for (String program : programs) {
                List<CourseId> pool = new ArrayList<>(shared);
                String code = program.substring(0, Math.min(3, program.length()));
                for (int k = 1; pool.size() < p.coursesPerSemester(); k++) {
                    pool.add(newCourse(credits, String.format("%s%02d-%d", code, semester, k), p, rng));
                }
                pools.computeIfAbsent(program, x -> new LinkedHashMap<>()).put(semester, pool);
            }
        }
        return new Curriculum(credits, pools, Curriculum.SEMESTERS);
    }

    private static CourseId newCourse(
            Map<CourseId, Integer> credits, String id, ExperimentConfig.OfferParams p, SplittableRandom rng) {
        CourseId course = new CourseId(id);
        credits.put(course, p.creditsMin() + rng.nextInt(p.creditsMax() - p.creditsMin() + 1));
        return course;
    }

    /** Grupos y cupos, a partir de la demanda de las asignaturas (cuántos perfiles las piden). */
    static Offer sections(
            Curriculum curriculum, Map<CourseId, Integer> demand, ExperimentConfig.OfferParams p, SplittableRandom rng) {
        Shift popular = p.sectionShift().mostPopular();
        List<SectionSeats> all = new ArrayList<>();
        for (Map.Entry<CourseId, Integer> entry : curriculum.credits().entrySet()) {
            CourseId course = entry.getKey();
            int credits = entry.getValue();
            int d = demand.getOrDefault(course, 0);

            int sections = Math.max(p.minSections(), Math.min(p.maxSections(),
                    (int) Math.ceil(d / p.targetGroupSize()) + rng.nextInt(2)));
            int blocks = blocksFor(credits, rng);
            int total = Math.max(sections * p.minSeatsPerSection(), (int) Math.ceil(d * p.slackFactor()));

            List<Section> built = new ArrayList<>();
            double[] weights = new double[sections];
            for (int i = 0; i < sections; i++) {
                Shift shift = p.sectionShift().pick(rng);
                SectionId id = new SectionId(String.format("%s-%02d", course.value(), i + 1));
                built.add(new Section(id, course, slots(blocks, shift, p.shiftAlignment(), rng), 0));
                double unevenness = 1 - p.seatUnevenness() + 2 * p.seatUnevenness() * rng.nextDouble();
                weights[i] = Math.max(0.05, unevenness) * (shift == popular ? p.popularSeatShare() : 1);
            }
            int[] seats = distribute(total, p.minSeatsPerSection(), weights);
            for (int i = 0; i < sections; i++) {
                Section s = built.get(i);
                all.add(new SectionSeats(new Section(s.id(), s.course(), s.slots(), seats[i]), seats[i]));
            }
        }
        return new Offer(all, curriculum.courses(), new EnrollmentRules(p.maxCredits()));
    }

    /** Bloques por semana: 2 créditos → 1 o 2; 3 → 2; 4 → 2 o 3. */
    private static int blocksFor(int credits, SplittableRandom rng) {
        int lo = credits <= 2 ? 1 : 2;
        int hi = credits <= 2 ? 2 : credits == 3 ? 2 : 3;
        return lo + rng.nextInt(hi - lo + 1);
    }

    /** Bloques en días distintos; cada uno cae en la jornada del grupo con probabilidad {@code alignment}. */
    private static List<TimeSlot> slots(int blocks, Shift shift, double alignment, SplittableRandom rng) {
        List<Weekday> days = new ArrayList<>(Shift.DAYS);
        Collections.shuffle(days, rng);
        List<TimeSlot> slots = new ArrayList<>();
        for (int i = 0; i < blocks; i++) {
            int[] starts = rng.nextDouble() < alignment ? shift.blockStarts() : Shift.BLOCK_STARTS;
            int hour = starts[rng.nextInt(starts.length)];
            slots.add(TimeSlot.of(days.get(i), hour * 60, (hour + 2) * 60).orElseThrow());
        }
        slots.sort((a, b) -> a.day() != b.day() ? a.day().compareTo(b.day()) : Integer.compare(a.start(), b.start()));
        return slots;
    }

    /** Reparte {@code total} cupos: el mínimo para cada grupo y el resto por pesos (mayor resto). */
    static int[] distribute(int total, int minimum, double[] weights) {
        int n = weights.length;
        int[] seats = new int[n];
        Arrays.fill(seats, minimum);
        int remaining = total - n * minimum;
        double sum = Arrays.stream(weights).sum();
        double[] remainder = new double[n];
        int given = 0;
        for (int i = 0; i < n; i++) {
            double share = remaining * weights[i] / sum;
            int whole = (int) Math.floor(share);
            seats[i] += whole;
            remainder[i] = share - whole;
            given += whole;
        }
        Set<Integer> used = new HashSet<>();
        for (int left = remaining - given; left > 0; left--) {
            int best = -1;
            for (int i = 0; i < n; i++) {
                if (!used.contains(i) && (best < 0 || remainder[i] > remainder[best])) {
                    best = i;
                }
            }
            seats[best]++;
            used.add(best);
        }
        return seats;
    }
}
