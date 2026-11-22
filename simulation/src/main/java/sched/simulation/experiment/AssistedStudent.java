package sched.simulation.experiment;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SplittableRandom;
import sched.application.Alternatives;
import sched.application.Combinations;
import sched.application.ScoredSchedule;
import sched.domain.CourseId;
import sched.domain.Schedule;
import sched.domain.Section;
import sched.domain.SectionId;
import sched.domain.StudentContext;
import sched.domain.StudentId;

/**
 * Condición B: matrícula asistida por el motor.
 *
 * <ol>
 *   <li>Pide las combinaciones válidas (con cupo y sin cruces según la réplica del motor), con sus
 *       preferencias de jornada como ventanas a evitar, y elige una: la primera con probabilidad
 *       {@code pickBestProbability} y si no una al azar entre las primeras {@code pickTop}.</li>
 *   <li>Si el motor no da combinaciones con todas las asignaturas, quita el mínimo necesario, con
 *       el mismo criterio que A (ver docs/simulador.md): primero las que no tienen ningún grupo con
 *       cupo; si aún no hay combinación, la asignatura cuya eliminación permite una, prefiriendo la
 *       de menor prioridad (la última de la lista del perfil) entre las que lo logran; y repite.
 *       Las quitadas cuentan como no matriculadas.</li>
 *   <li>Envía sus grupos uno a uno. Si la plataforma rechaza uno (el cupo se acabó entre la
 *       consulta y el envío, por el tiempo de decisión o por la latencia del evento de cupo),
 *       pide alternativas con su selección actual y sigue con la primera: retira los grupos
 *       inscritos que esa alternativa cambia (antes de inscribir los nuevos, para no cruzarse
 *       consigo mismo) y envía lo que falta.</li>
 *   <li>Si las alternativas vienen vacías, aplica la misma regla de abandono (retirando la
 *       asignatura si ya estaba inscrita). El tope {@code maxAlternativeRounds} acota los
 *       intentos.</li>
 * </ol>
 *
 * <p>Regla de abandono: se quitan primero las asignaturas sin ningún grupo con cupo en la
 * plataforma (las ya inscritas no cuentan); si aún no hay combinación, la asignatura cuya
 * eliminación permite una (probando con el motor), prefiriendo la de menor prioridad entre las que
 * lo logran; si ninguna sola basta, la de menor prioridad; y se repite. Esas consultas al motor
 * cuentan en {@code engine_calls} y en el tiempo.
 *
 * <p>Al pedir alternativas informa al motor qué grupos ya tiene inscritos ({@code enrolled}), para
 * que no trate como sin cupo uno cuyo último cupo tomó él mismo. {@code GenerateCombinations} no
 * necesita algo análogo aquí: solo se llama antes de inscribir nada (y al abandonar una
 * asignatura antes de la primera inscripción); desde que hay grupos inscritos el flujo usa
 * alternativas, que sí reciben {@code enrolled}.
 *
 * <p>Cada combinación y alternativa que entrega el motor se verifica contra la plataforma: si
 * alguna tuviera cruces el experimento falla con error.
 */
final class AssistedStudent {

    private final Profile profile;
    private final Map<CourseId, List<Section>> sections;
    private final Platform platform;
    private final EngineBridge engine;
    private final EventQueue queue;
    private final ExperimentConfig config;
    private final Tally tally;
    private final SplittableRandom timeRandom;
    private final SplittableRandom choiceRandom;

    private final List<CourseId> active = new ArrayList<>();
    private Map<CourseId, Section> plan = new HashMap<>();
    private int rounds;

    AssistedStudent(Profile profile, Map<CourseId, List<Section>> sections, Platform platform, EngineBridge engine, EventQueue queue,
            ExperimentConfig config, Tally tally) {
        this.profile = profile;
        this.sections = sections;
        this.platform = platform;
        this.engine = engine;
        this.queue = queue;
        this.config = config;
        this.tally = tally;
        this.timeRandom = Seeds.rng(config.seed(), "behavior-time", profile.index());
        this.choiceRandom = Seeds.rng(config.seed(), "behavior-choice", profile.index());
    }

    void start() {
        active.addAll(profile.courses());
        requestCombinations();
    }

    private StudentContext context(List<CourseId> courses) {
        return new StudentContext(new StudentId(profile.id()), List.copyOf(courses), profile.preference().avoiding());
    }

    private void requestCombinations() {
        double spent = 0;
        while (true) {
            if (active.isEmpty()) {
                confirm(spent);
                return;
            }
            EngineBridge.Answer<Combinations> answer = engine.combinations(context(active));
            count(answer.nanos());
            spent += answer.seconds();
            List<ScoredSchedule> found = answer.value().schedules();
            if (found.isEmpty()) {
                // Sin combinación completa: abandona lo mínimo necesario (regla de abandono de la clase).
                List<CourseId> impossible = active.stream().filter(c -> !hasSeats(c)).toList();
                if (!impossible.isEmpty()) {
                    active.removeAll(impossible);
                    spent += config.times().readRejection().sample(timeRandom);
                    continue;
                }
                boolean dropped = false;
                for (int i = active.size() - 1; active.size() > 1 && i >= 0 && !dropped; i--) {
                    List<CourseId> rest = new ArrayList<>(active);
                    CourseId candidate = rest.remove(i);
                    EngineBridge.Answer<Combinations> trial = engine.combinations(context(rest));
                    count(trial.nanos());
                    spent += trial.seconds();
                    if (!trial.value().schedules().isEmpty()) {
                        active.remove(candidate);
                        found = trial.value().schedules();
                        dropped = true;
                    }
                }
                if (!dropped) {
                    active.removeLast();
                    spent += config.times().readRejection().sample(timeRandom);
                    continue;
                }
            }
            List<ScoredSchedule> schedules = found;
            schedules.forEach(s -> verify(s.schedule(), "una combinación"));
            Schedule picked = pickCombination(schedules);
            queue.after(spent + config.times().reviewCombinations().sample(timeRandom), () -> {
                plan = byCourse(picked);
                submitNext();
            });
            return;
        }
    }

    /** La asignatura tiene un grupo con cupo en la plataforma, o el estudiante ya está inscrito en ella. */
    private boolean hasSeats(CourseId course) {
        return platform.enrolledOf(profile.index()).containsKey(course)
                || sections.getOrDefault(course, List.of()).stream().anyMatch(s -> platform.available(s.id()) > 0);
    }

    /** Quita la asignatura del plan, retirándola si estaba inscrita; devuelve el tiempo que tomó. */
    private double abandon(CourseId course) {
        active.remove(course);
        plan.remove(course);
        if (platform.enrolledOf(profile.index()).containsKey(course)) {
            platform.withdraw(profile.index(), course);
            tally.withdrawals++;
            return config.times().withdraw().sample(timeRandom);
        }
        return 0;
    }

    private Schedule pickCombination(List<ScoredSchedule> schedules) {
        int index = 0;
        if (choiceRandom.nextDouble() >= config.engine().pickBestProbability()) {
            index = choiceRandom.nextInt(Math.min(schedules.size(), config.engine().pickTop()));
        }
        return schedules.get(index).schedule();
    }

    /** Siguiente asignatura del plan cuyo grupo aún no está inscrito. */
    private void submitNext() {
        Map<CourseId, Section> mine = platform.enrolledOf(profile.index());
        for (CourseId course : active) {
            Section wanted = plan.get(course);
            Section has = mine.get(course);
            if (has == null || !has.id().equals(wanted.id())) {
                queue.after(config.times().submitChosen().sample(timeRandom), () -> submit(course));
                return;
            }
        }
        confirm(0);
    }

    private void submit(CourseId course) {
        switch (platform.submit(profile.index(), plan.get(course))) {
            case ACCEPTED -> submitNext();
            case CLASH -> {
                // Por diseño no debería ocurrir; se cuenta (la plataforma es quien lo mide) y se
                // sigue por el mismo camino que un rechazo de cupo.
                tally.rTraslape++;
                afterRejection();
            }
            case FULL -> {
                tally.rCupo++;
                afterRejection();
            }
        }
    }

    private void afterRejection() {
        queue.after(config.times().readRejection().sample(timeRandom), this::askAlternatives);
    }

    private void askAlternatives() {
        double spent = 0;
        while (true) {
            if (active.isEmpty() || rounds >= config.behavior().maxAlternativeRounds()) {
                confirm(spent);
                return;
            }
            EngineBridge.Answer<Alternatives> answer = alternativesFor(active);
            count(answer.nanos());
            spent += answer.seconds();
            List<Alternatives.Alternative> found = answer.value().alternatives();
            if (found.isEmpty()) {
                List<CourseId> impossible = active.stream().filter(c -> !hasSeats(c)).toList();
                if (!impossible.isEmpty()) {
                    for (CourseId c : impossible) {
                        spent += abandon(c);
                    }
                    spent += config.times().readRejection().sample(timeRandom);
                    continue;
                }
                boolean dropped = false;
                for (int i = active.size() - 1; active.size() > 1 && i >= 0 && !dropped; i--) {
                    List<CourseId> rest = new ArrayList<>(active);
                    CourseId candidate = rest.remove(i);
                    EngineBridge.Answer<Alternatives> trial = alternativesFor(rest);
                    count(trial.nanos());
                    spent += trial.seconds();
                    if (!trial.value().alternatives().isEmpty()) {
                        spent += abandon(candidate);
                        found = trial.value().alternatives();
                        dropped = true;
                    }
                }
                if (!dropped) {
                    spent += abandon(active.getLast());
                    spent += config.times().readRejection().sample(timeRandom);
                    continue;
                }
            }
            found.forEach(a -> verify(a.schedule(), "una alternativa"));
            Alternatives.Alternative first = found.getFirst();
            queue.after(spent + config.times().reviewAlternatives().sample(timeRandom), () -> adopt(first));
            return;
        }
    }

    /** Pide alternativas para las asignaturas dadas con la selección actual y los grupos ya inscritos. */
    private EngineBridge.Answer<Alternatives> alternativesFor(List<CourseId> courses) {
        Map<CourseId, SectionId> selection = new LinkedHashMap<>();
        Set<SectionId> enrolled = new HashSet<>();
        Map<CourseId, Section> mine = platform.enrolledOf(profile.index());
        for (CourseId course : courses) {
            SectionId wanted = plan.get(course).id();
            selection.put(course, wanted);
            Section has = mine.get(course);
            if (has != null && has.id().equals(wanted)) {
                enrolled.add(wanted);
            }
        }
        return engine.alternatives(context(courses), selection, enrolled);
    }

    private void adopt(Alternatives.Alternative alternative) {
        rounds++;
        Map<CourseId, Section> next = byCourse(alternative.schedule());
        double delay = 0;
        for (CourseId course : active) {
            Section enrolled = platform.enrolledOf(profile.index()).get(course);
            if (enrolled != null && !enrolled.id().equals(next.get(course).id())) {
                platform.withdraw(profile.index(), course);
                tally.withdrawals++;
                delay += config.times().withdraw().sample(timeRandom);
            }
        }
        plan = next;
        queue.after(delay, this::submitNext);
    }

    private void confirm(double extra) {
        queue.after(extra + config.times().confirm().sample(timeRandom), () -> tally.finishedAt = queue.now());
    }

    private void count(long nanos) {
        tally.engineCalls++;
        tally.engineNanos += nanos;
    }

    private Map<CourseId, Section> byCourse(Schedule schedule) {
        Map<CourseId, Section> map = new HashMap<>();
        schedule.sections().forEach(s -> map.put(s.course(), s));
        return map;
    }

    /** La plataforma, independiente del motor, comprueba que lo que entregó no tenga cruces. */
    private void verify(Schedule schedule, String what) {
        if (Platform.hasClash(schedule.sections())) {
            throw new IllegalStateException("el motor entregó " + what + " con cruces para " + profile.id() + ": "
                    + schedule.sections().stream().map(s -> s.id().value()).toList());
        }
        List<CourseId> courses = schedule.sections().stream().map(Section::course).toList();
        if (courses.size() != active.size() || !courses.containsAll(active)) {
            throw new IllegalStateException("el motor entregó " + what + " que no cubre las asignaturas pedidas para "
                    + profile.id());
        }
    }
}
