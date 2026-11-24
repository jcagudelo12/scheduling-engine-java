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
 *       de enviar, con la probabilidad de conciencia de cruces propia del estudiante
 *       ({@link StudentTraits}), nota a simple vista un cruce y descarta el grupo sin gastar un
 *       envío.</li>
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
    private final StudentTraits traits;

    private final Deque<CourseId> pending = new ArrayDeque<>();
    private final Map<CourseId, Set<SectionId>> blocked = new HashMap<>();
    private final Map<CourseId, Set<SectionId>> avoided = new HashMap<>();
    private final Map<CourseId, Integer> retreats = new HashMap<>();
    /**
     * El estudiante ya se topó con un cruce (se lo rechazó la plataforma o lo notó él): desde ahí y
     * hasta confirmar está "resolviendo un cruce", que es el contexto de A13 (ver CalibrationReport).
     */
    private boolean resolvingClash;
    /** Grupos que el estudiante quería (se cruzaban) y vio llenos al volver: cupo perdido mientras resolvía el cruce. */
    private final Set<SectionId> lostWanted = new HashSet<>();
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
        this.traits = StudentTraits.draw(config, profile.index());
    }

    private double time(ExperimentConfig.Dist dist) {
        return traits.time(dist, timeRandom);
    }

    void start() {
        pending.addAll(profile.courses());
        nextCourse();
    }

    private void nextCourse() {
        if (pending.isEmpty()) {
            queue.after(time(config.times().confirm()), () -> tally.finishedAt = queue.now());
            return;
        }
        current = pending.poll();
        refresh();
        queue.after(time(config.times().browseCourse()), this::pick);
    }

    /** Carga la página de la asignatura: los cupos que se ven quedan fijos hasta la siguiente carga. */
    private void refresh() {
        seen = new HashMap<>();
        for (Section s : sections.getOrDefault(current, List.of())) {
            seen.put(s.id(), platform.available(s.id()));
        }
    }

    /** Tras un rechazo recarga la página, salvo con probabilidad {@code staleViewProbability}: sigue con los cupos viejos. */
    private void reloadAfterRejection() {
        if (choiceRandom.nextDouble() >= config.behavior().staleViewProbability()) {
            refresh();
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
                if (blockedHere.contains(s.id()) && lostWanted.add(s.id())) {
                    tally.rCupoInClash++;
                }
                continue;
            }
            withSeats.add(s);
            if (blockedHere.contains(s.id()) || avoidedHere.contains(s.id())) {
                continue;
            }
            if (clashesWithEnrolled(s) && choiceRandom.nextDouble() < traits.awareness()) {
                blockedHere.add(s.id());
                resolvingClash = true;
                continue;
            }
            candidates.add(s);
        }
        if (candidates.isEmpty()) {
            exhausted(withSeats);
            return;
        }
        chosen = weightedPick(candidates);
        queue.after(time(config.times().chooseSection()) + time(config.times().submitSection()), this::submit);
    }

    private Section weightedPick(List<Section> candidates) {
        double strength = config.behavior().preferenceStrength();
        double[] weights = candidates.stream()
                .mapToDouble(s -> Math.exp(strength * profile.preference().fractionIn(s)) * Popularity.of(config, s.id()))
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
            nextCourse();
            return;
        }
        submissions++;
        int before = platform.available(chosen.id());
        switch (platform.submit(profile.index(), chosen)) {
            case ACCEPTED -> {
                if (resolvingClash && before <= config.behavior().nearLossSeats()) {
                    tally.nearLoss++;
                }
                nextCourse();
            }
            case CLASH -> {
                tally.rTraslape++;
                resolvingClash = true;
                blocked.get(current).add(chosen.id());
                reloadAfterRejection();
                queue.after(time(config.times().readRejection()), this::pick);
            }
            case FULL -> {
                tally.rCupo++;
                seen.put(chosen.id(), 0); // el rechazo le dice que ese grupo está lleno, aunque no recargue
                if (resolvingClash) {
                    tally.rCupoInClash++;
                }
                reloadAfterRejection();
                queue.after(time(config.times().readRejection()), this::pick);
            }
        }
    }

    /** Ningún grupo elegible: aplaza la asignatura o retrocede. {@code withSeats}: grupos con cupo ahora. */
    private void exhausted(List<Section> withSeats) {
        double read = time(config.times().readRejection());
        boolean defer = withSeats.isEmpty()
                || choiceRandom.nextDouble() < config.behavior().deferOnExhaustProbability()
                || retreats.getOrDefault(current, 0) >= config.behavior().maxRetreatsPerCourse();
        List<CourseId> victims = defer ? List.of() : victimsFor(withSeats);
        if (defer || victims.isEmpty()) {
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
        queue.after(read + time(config.times().withdraw()), this::pick);
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
