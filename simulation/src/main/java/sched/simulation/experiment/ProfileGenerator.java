package sched.simulation.experiment;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SplittableRandom;
import sched.domain.CourseId;

/**
 * Perfiles sintéticos con las distribuciones de la consulta (programa, semestre y número de
 * asignaturas) y supuestos declarados (jornada preferida, ventana de matrícula).
 *
 * <p>Los perfiles salen del flujo {@code profiles} y las llegadas del flujo {@code arrivals}: son
 * independientes entre sí y de la oferta y el comportamiento, así que no cambian entre
 * condiciones.
 */
final class ProfileGenerator {

    private ProfileGenerator() {
    }

    static List<Profile> generate(
            ExperimentConfig config, Calibration calibration, Curriculum curriculum, int maxCredits) {
        SplittableRandom random = config.rng("profiles", 0);
        List<Calibration.Category> programs = calibration.programs();

        List<Profile> profiles = new ArrayList<>();
        for (int i = 0; i < config.profiles(); i++) {
            String program = Calibration.pick(random, programs).id();
            int semester = Calibration.uniformIn(random, Calibration.pick(random, calibration.semesters()));
            int intended = Calibration.uniformIn(random, Calibration.pick(random, calibration.courseCounts()));
            Shift preference = config.population().preference().pick(random);
            int window = Seeds.pick(random, Seeds.toArray(config.arrivals().windowWeights())) + 1;
            List<CourseId> courses = chooseCourses(curriculum, program, semester, intended, maxCredits, random);
            profiles.add(new Profile(i, String.format("P%04d", i + 1), program, semester, intended, window,
                    preference, courses, 0));
        }
        return withArrivals(config, profiles);
    }

    /**
     * Asignaturas del semestre del estudiante (en orden aleatorio) y, si pide más de las que hay,
     * las de semestres vecinos (pendientes y adelantadas). Se salta las que harían superar el tope
     * de créditos del periodo.
     */
    private static List<CourseId> chooseCourses(
            Curriculum curriculum, String program, int semester, int wanted, int maxCredits, SplittableRandom random) {
        Set<CourseId> candidates = new LinkedHashSet<>();
        for (int distance = 0; distance < curriculum.semesters(); distance++) {
            for (int s : distance == 0 ? new int[] {semester} : new int[] {semester - distance, semester + distance}) {
                if (s >= 1 && s <= curriculum.semesters()) {
                    List<CourseId> pool = new ArrayList<>(curriculum.pool(program, s));
                    Collections.shuffle(pool, random);
                    candidates.addAll(pool);
                }
            }
        }
        List<CourseId> chosen = new ArrayList<>();
        int credits = 0;
        for (CourseId course : candidates) {
            if (chosen.size() >= wanted) {
                break;
            }
            int c = curriculum.credits().getOrDefault(course, 0);
            if (maxCredits > 0 && credits + c > maxCredits) {
                continue;
            }
            chosen.add(course);
            credits += c;
        }
        return chosen;
    }

    /** Poisson dentro de cada ventana: tiempos entre llegadas exponenciales, en orden de perfil. */
    private static List<Profile> withArrivals(ExperimentConfig config, List<Profile> profiles) {
        SplittableRandom random = config.rng("arrivals", 0);
        double ratePerSecond = config.arrivals().arrivalsPerMinute() / 60.0;
        Map<Integer, Double> clock = new HashMap<>();
        List<Profile> result = new ArrayList<>();
        for (Profile p : profiles) {
            double start = config.arrivals().windowStartSeconds().get(p.window() - 1);
            double t = clock.getOrDefault(p.window(), start) + random.nextExponential() / ratePerSecond;
            clock.put(p.window(), t);
            result.add(p.withArrival(t));
        }
        return result;
    }

    static Map<CourseId, Integer> demand(List<Profile> profiles) {
        Map<CourseId, Integer> demand = new HashMap<>();
        for (Profile p : profiles) {
            p.courses().forEach(c -> demand.merge(c, 1, Integer::sum));
        }
        return demand;
    }
}
