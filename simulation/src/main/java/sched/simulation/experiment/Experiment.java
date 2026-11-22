package sched.simulation.experiment;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;
import sched.domain.CourseId;
import sched.domain.Section;
import sched.simulation.Scenario;

/**
 * Experimento por simulación pareada (diseño intra-sujeto): cada perfil matricula en las dos
 * condiciones sobre la misma oferta y la misma secuencia de llegadas.
 *
 * <p>Simulación de eventos discretos con reloj simulado: cada condición se corre completa, con su
 * propia plataforma y, en B, su propio motor, y los estudiantes compiten por los mismos cupos.
 */
public final class Experiment {

    /** Lo común a las dos condiciones: calibración, oferta y perfiles (con sus llegadas). */
    public record Setup(Calibration calibration, Offer offer, List<Profile> profiles) {
    }

    /** Resultados de una corrida completa; las listas van en el orden de los perfiles. */
    public record Result(
            ExperimentConfig config, Setup setup, List<StudentResult> reactive, List<StudentResult> assisted) {
    }

    private Experiment() {
    }

    public static Setup prepare(ExperimentConfig config) {
        Calibration calibration = Calibration.load(config.calibrationFile());
        List<String> programs = calibration.programs().stream().map(Calibration.Category::id).toList();
        ExperimentConfig.OfferParams params = config.offer();

        if (params.file() != null && !params.file().isBlank()) {
            Scenario scenario = Scenario.load(Path.of(params.file()));
            Offer offer = new Offer(scenario.sections(), scenario.courses(), scenario.rules());
            Curriculum curriculum = Curriculum.fromCourses(offer.courses(), programs);
            List<Profile> profiles =
                    ProfileGenerator.generate(config, calibration, curriculum, offer.rules().maxCredits());
            return new Setup(calibration, offer, profiles);
        }

        Curriculum curriculum = OfferGenerator.curriculum(params, programs, config.rng("offer", 0));
        List<Profile> profiles = ProfileGenerator.generate(config, calibration, curriculum, params.maxCredits());
        // Mismo flujo de oferta (continúa tras el plan de estudios), no el de los perfiles.
        Offer offer = OfferGenerator.sections(
                curriculum, ProfileGenerator.demand(profiles), params, offerStreamAfterCurriculum(config, params, programs));
        return new Setup(calibration, offer, profiles);
    }

    /** Reconstruye el flujo de oferta en el punto en que terminó el plan de estudios. */
    private static SplittableRandom offerStreamAfterCurriculum(
            ExperimentConfig config, ExperimentConfig.OfferParams params, List<String> programs) {
        SplittableRandom random = config.rng("offer", 0);
        OfferGenerator.curriculum(params, programs, random);
        return random;
    }

    public static Result run(ExperimentConfig config) {
        return run(config, prepare(config));
    }

    public static Result run(ExperimentConfig config, Setup setup) {
        return new Result(config, setup,
                runCondition(config, setup.offer(), setup.profiles(), Condition.REACTIVE),
                runCondition(config, setup.offer(), setup.profiles(), Condition.ASSISTED));
    }

    /** Corre una condición con su propia plataforma (y, en B, su propio motor). */
    public static List<StudentResult> runCondition(
            ExperimentConfig config, Offer offer, List<Profile> profiles, Condition condition) {
        EventQueue queue = new EventQueue();
        boolean assisted = condition == Condition.ASSISTED;
        EngineBridge engine = assisted ? new EngineBridge(offer, config.engine()) : null;
        double latency = config.platform().seatEventLatencySeconds();
        Platform platform = new Platform(offer.sections(), (section, available) -> {
            if (engine != null) {
                // El cambio se numera al emitirlo y llega a la ingesta tras la latencia, en orden.
                long seq = engine.reserveSeq();
                queue.after(latency, () -> engine.deliverSeats(seq, section, available));
            }
        });

        Map<CourseId, List<Section>> sections = offer.sectionsByCourse();
        List<Tally> tallies = new ArrayList<>();
        for (Profile profile : profiles) {
            Tally tally = new Tally();
            tallies.add(tally);
            Runnable start = assisted
                    ? new AssistedStudent(profile, sections, platform, engine, queue, config, tally)::start
                    : new ReactiveStudent(profile, sections, platform, queue, config, tally)::start;
            queue.at(profile.arrivalSeconds(), start);
        }
        queue.run();

        platform.verifyInvariants();
        if (engine != null) {
            engine.verifyConsistentWith(platform, offer);
        }

        List<StudentResult> results = new ArrayList<>();
        for (int i = 0; i < profiles.size(); i++) {
            Profile profile = profiles.get(i);
            Tally t = tallies.get(i);
            if (Double.isNaN(t.finishedAt)) {
                throw new IllegalStateException("el estudiante " + profile.id() + " no terminó");
            }
            int enrolled = platform.enrolledOf(profile.index()).size();
            results.add(new StudentResult(profile, condition, t.finishedAt - profile.arrivalSeconds(),
                    t.rTraslape, t.rCupo, enrolled, profile.courses().size() - enrolled, t.engineCalls,
                    t.engineNanos, t.withdrawals, t.rCupoInClash, t.nearLoss));
        }
        return results;
    }
}
