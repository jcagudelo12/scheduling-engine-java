package sched.simulation.experiment;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
 *   <li>Si el motor no da combinaciones con todas las asignaturas, quita la de menor prioridad (la
 *       última) y vuelve a pedir; la quitada cuenta como no matriculada.</li>
 *   <li>Envía sus grupos uno a uno. Si la plataforma rechaza uno (el cupo se acabó entre la
 *       consulta y el envío, por el tiempo de decisión o por la latencia del evento de cupo),
 *       pide alternativas con su selección actual y sigue con la primera: retira los grupos
 *       inscritos que esa alternativa cambia (antes de inscribir los nuevos, para no cruzarse
 *       consigo mismo) y envía lo que falta.</li>
 *   <li>Si las alternativas vienen vacías, quita la última asignatura (retirándola si ya estaba
 *       inscrita) y vuelve a pedir. El tope {@code maxAlternativeRounds} acota los intentos.</li>
 * </ol>
 *
 * <p>Cada combinación y alternativa que entrega el motor se verifica contra la plataforma: si
 * alguna tuviera cruces el experimento falla con error.
 */
final class AssistedStudent {

    private final Profile profile;
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

    AssistedStudent(Profile profile, Platform platform, EngineBridge engine, EventQueue queue,
            ExperimentConfig config, Tally tally) {
        this.profile = profile;
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

    private StudentContext context() {
        return new StudentContext(new StudentId(profile.id()), List.copyOf(active), profile.preference().avoiding());
    }

    private void requestCombinations() {
        if (active.isEmpty()) {
            confirm(0);
            return;
        }
        EngineBridge.Answer<Combinations> answer = engine.combinations(context());
        count(answer.nanos());
        List<ScoredSchedule> schedules = answer.value().schedules();
        schedules.forEach(s -> verify(s.schedule(), "una combinación"));
        if (schedules.isEmpty()) {
            active.removeLast();
            queue.after(answer.seconds() + config.times().readRejection().sample(timeRandom), this::requestCombinations);
            return;
        }
        Schedule picked = pickCombination(schedules);
        queue.after(answer.seconds() + config.times().reviewCombinations().sample(timeRandom), () -> {
            plan = byCourse(picked);
            submitNext();
        });
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
        if (active.isEmpty() || rounds >= config.behavior().maxAlternativeRounds()) {
            confirm(0);
            return;
        }
        Map<CourseId, SectionId> selection = new LinkedHashMap<>();
        for (CourseId course : active) {
            selection.put(course, plan.get(course).id());
        }
        EngineBridge.Answer<Alternatives> answer = engine.alternatives(context(), selection);
        count(answer.nanos());
        List<Alternatives.Alternative> alternatives = answer.value().alternatives();
        alternatives.forEach(a -> verify(a.schedule(), "una alternativa"));
        if (alternatives.isEmpty()) {
            CourseId dropped = active.removeLast();
            plan.remove(dropped);
            double withdrawal = 0;
            if (platform.enrolledOf(profile.index()).containsKey(dropped)) {
                platform.withdraw(profile.index(), dropped);
                tally.withdrawals++;
                withdrawal = config.times().withdraw().sample(timeRandom);
            }
            queue.after(answer.seconds() + withdrawal + config.times().readRejection().sample(timeRandom),
                    this::askAlternatives);
            return;
        }
        Alternatives.Alternative first = alternatives.getFirst();
        queue.after(answer.seconds() + config.times().reviewAlternatives().sample(timeRandom), () -> adopt(first));
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
