package sched.simulation.experiment;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SplittableRandom;
import sched.domain.CourseId;
import sched.domain.Section;
import sched.domain.SectionId;

/**
 * Condición A (línea base): matrícula reactiva, como la plataforma actual.
 *
 * <p>Por cada asignatura, en orden de prioridad: carga la oferta (los cupos que ve quedan fijos hasta
 * la siguiente carga), navega, elige un grupo (por jornada
 * preferida con ruido, entre los que ve con cupo) y lo envía; la plataforma lo acepta o rechaza.
 * <ul>
 *   <li>Rechazo por cruce o cupo: lee el mensaje y prueba otro grupo de la misma asignatura. Antes
 *       de enviar, con probabilidad {@code clashAwareness}, nota a simple vista un cruce y descarta
 *       el grupo sin gastar un envío.</li>
 *   <li>Sin grupos con cupo en la asignatura: la aplaza.</li>
 *   <li>Si agota los grupos con cupo por cruces: con probabilidad {@code deferOnExhaustProbability}
 *       (o al agotar sus retrocesos) la aplaza; si no, retrocede: retira un grupo ya inscrito que
 *       choque, reintenta la asignatura y deja la retirada para el final de la cola, evitando el
 *       grupo que retiró.</li>
 *   <li>Termina siempre: {@code maxSubmissions} acota los envíos y los retrocesos están
 *       limitados por asignatura.</li>
 * </ul>
 */
final class ReactiveStudent {

    private final Profile profile;
    private final Map<CourseId, List<Section>> sections;
    private final Platform platform;
    private final EventQueue queue;
    private final ExperimentConfig config;
    private final Tally tally;
    private final SplittableRandom timeRandom;
    private final SplittableRandom choiceRandom;

    private final Deque<CourseId> pending = new ArrayDeque<>();
    private final Map<CourseId, Set<SectionId>> blocked = new HashMap<>();
    private final Map<CourseId, Set<SectionId>> avoided = new HashMap<>();
    private final Map<CourseId, Integer> retreats = new HashMap<>();
    private final Set<CourseId> clashPending = new HashSet<>();
    /** Cupos que el estudiante ve en la página de la asignatura: los de la última vez que se cargó. */
    private Map<SectionId, Integer> seen = new HashMap<>();
    private CourseId current;
    private Section chosen;
    private int submissions;

    ReactiveStudent(Profile profile, Map<CourseId, List<Section>> sections, Platform platform, EventQueue queue,
            ExperimentConfig config, Tally tally) {
        this.profile = profile;
        this.sections = sections;
        this.platform = platform;
        this.queue = queue;
        this.config = config;
        this.tally = tally;
        this.timeRandom = Seeds.rng(config.seed(), "behavior-time", profile.index());
        this.choiceRandom = Seeds.rng(config.seed(), "behavior-choice", profile.index());
    }

    void start() {
        pending.addAll(profile.courses());
        nextCourse();
    }

    private void nextCourse() {
        if (pending.isEmpty()) {
            queue.after(config.times().confirm().sample(timeRandom), () -> tally.finishedAt = queue.now());
            return;
        }
        current = pending.poll();
        refresh();
        queue.after(config.times().browseCourse().sample(timeRandom), this::pick);
    }

    /** Carga la página de la asignatura: los cupos que se ven quedan fijos hasta la siguiente carga. */
    private void refresh() {
        seen = new HashMap<>();
        for (Section s : sections.getOrDefault(current, List.of())) {
            seen.put(s.id(), platform.available(s.id()));
        }
    }

    /**
     * El estudiante elige un grupo entre los que veía con cupo cuando se cargó la página; entre
     * esa carga y el envío pasa tiempo, y ahí otros pueden tomar el cupo.
     */
    private void pick() {
        Set<SectionId> blockedHere = blocked.computeIfAbsent(current, k -> new HashSet<>());
        Set<SectionId> avoidedHere = avoided.computeIfAbsent(current, k -> new HashSet<>());
        List<Section> candidates = new ArrayList<>();
        List<Section> withSeats = new ArrayList<>();
        for (Section s : sections.getOrDefault(current, List.of())) {
            if (seen.getOrDefault(s.id(), 0) <= 0) {
                continue;
            }
            withSeats.add(s);
            if (blockedHere.contains(s.id()) || avoidedHere.contains(s.id())) {
                continue;
            }
            if (clashesWithEnrolled(s) && choiceRandom.nextDouble() < config.behavior().clashAwareness()) {
                blockedHere.add(s.id());
                continue;
            }
            candidates.add(s);
        }
        if (candidates.isEmpty()) {
            exhausted(withSeats);
            return;
        }
        chosen = weightedPick(candidates);
        queue.after(config.times().chooseAndSubmit().sample(timeRandom), this::submit);
    }

    private Section weightedPick(List<Section> candidates) {
        double strength = config.behavior().preferenceStrength();
        double[] weights = candidates.stream()
                .mapToDouble(s -> Math.exp(strength * profile.preference().fractionIn(s)))
                .toArray();
        return candidates.get(Seeds.pick(choiceRandom, weights));
    }

    private boolean clashesWithEnrolled(Section section) {
        for (Section enrolled : platform.enrolledOf(profile.index()).values()) {
            if (Platform.clash(section, enrolled)) {
                return true;
            }
        }
        return false;
    }

    private void submit() {
        if (submissions >= config.behavior().maxSubmissions()) {
            // Tope de seguridad: el resto queda sin inscribir.
            pending.clear();
            clashPending.clear();
            nextCourse();
            return;
        }
        submissions++;
        int before = platform.available(chosen.id());
        switch (platform.submit(profile.index(), chosen)) {
            case ACCEPTED -> {
                if (clashPending.remove(current) && before <= config.behavior().nearLossSeats()) {
                    tally.nearLoss++;
                }
                nextCourse();
            }
            case CLASH -> {
                tally.rTraslape++;
                clashPending.add(current);
                blocked.get(current).add(chosen.id());
                refresh();
                queue.after(config.times().readRejection().sample(timeRandom), this::pick);
            }
            case FULL -> {
                tally.rCupo++;
                if (clashPending.contains(current)) {
                    tally.rCupoInClash++;
                }
                refresh();
                queue.after(config.times().readRejection().sample(timeRandom), this::pick);
            }
        }
    }

    /** Ningún grupo elegible: aplaza la asignatura o retrocede. {@code withSeats}: grupos con cupo ahora. */
    private void exhausted(List<Section> withSeats) {
        double read = config.times().readRejection().sample(timeRandom);
        boolean defer = withSeats.isEmpty()
                || choiceRandom.nextDouble() < config.behavior().deferOnExhaustProbability()
                || retreats.getOrDefault(current, 0) >= config.behavior().maxRetreatsPerCourse();
        List<CourseId> victims = defer ? List.of() : victimsFor(withSeats);
        if (defer || victims.isEmpty()) {
            clashPending.remove(current);
            queue.after(read, this::nextCourse);
            return;
        }
        CourseId victim = victims.get(choiceRandom.nextInt(victims.size()));
        Section retired = platform.enrolledOf(profile.index()).get(victim);
        platform.withdraw(profile.index(), victim);
        tally.withdrawals++;
        retreats.merge(current, 1, Integer::sum);
        avoided.computeIfAbsent(victim, k -> new HashSet<>()).add(retired.id());
        blocked.computeIfAbsent(victim, k -> new HashSet<>()).clear();
        blocked.get(current).clear();
        refresh();
        pending.addLast(victim);
        queue.after(read + config.times().withdraw().sample(timeRandom), this::pick);
    }

    /** Asignaturas ya inscritas cuyo grupo se cruza con algún grupo con cupo de la actual. */
    private List<CourseId> victimsFor(List<Section> withSeats) {
        List<CourseId> victims = new ArrayList<>();
        platform.enrolledOf(profile.index()).forEach((course, enrolled) -> {
            if (withSeats.stream().anyMatch(s -> Platform.clash(s, enrolled))) {
                victims.add(course);
            }
        });
        return victims;
    }
}
