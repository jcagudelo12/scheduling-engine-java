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

    /**
     * Llegadas por ventana: una fracción {@code rushShare} entra en la avalancha de la apertura
     * (exponencial desde el inicio de la ventana) y el resto llega como proceso de Poisson. Los
     * tiempos de la ventana se ordenan y se asignan a sus perfiles en orden de perfil.
     */
    private static List<Profile> withArrivals(ExperimentConfig config, List<Profile> profiles) {
        SplittableRandom random = config.rng("arrivals", 0);
        ExperimentConfig.ArrivalParams a = config.arrivals();
        double ratePerSecond = a.arrivalsPerMinute() / 60.0;
        Map<Integer, List<Integer>> byWindow = new HashMap<>();
        for (int i = 0; i < profiles.size(); i++) {
            byWindow.computeIfAbsent(profiles.get(i).window(), k -> new ArrayList<>()).add(i);
        }
        double[] arrival = new double[profiles.size()];
        for (Map.Entry<Integer, List<Integer>> entry : byWindow.entrySet().stream()
                .sorted(Map.Entry.comparingByKey()).toList()) {
            double start = a.windowStartSeconds().get(entry.getKey() - 1);
            List<Double> times = new ArrayList<>();
            double clock = start;
            for (int ignored : entry.getValue()) {
                if (random.nextDouble() < a.rushShare()) {
                    times.add(start + random.nextExponential() * a.rushMeanSeconds());
                } else {
                    clock += random.nextExponential() / ratePerSecond;
                    times.add(clock);
                }
            }
            Collections.sort(times);
            for (int k = 0; k < times.size(); k++) {
                arrival[entry.getValue().get(k)] = times.get(k);
            }
        }
        List<Profile> result = new ArrayList<>();
        for (int i = 0; i < profiles.size(); i++) {
            result.add(profiles.get(i).withArrival(arrival[i]));
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
