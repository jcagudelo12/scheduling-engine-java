package sched.simulation.experiment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sched.domain.CourseId;
import sched.domain.Course;
import sched.domain.EnrollmentRules;
import sched.domain.Section;
import sched.domain.SectionId;
import sched.domain.SectionSeats;
import sched.domain.TimeSlot;
import sched.domain.Weekday;

class ExperimentTest {

    private static final Path BASE = Path.of("experiments/base.json");

    /** Corrida chica y reproducible: el tiempo del motor no se suma (si no, depende de la máquina). */
    private static ExperimentConfig small(long seed, int profiles) {
        ExperimentConfig base = ExperimentConfig.defaults().withSeed(seed).withProfiles(profiles);
        ExperimentConfig.EngineParams e = base.engine();
        return base.withEngine(new ExperimentConfig.EngineParams(
                e.solver(), e.maxResults(), e.maxAlternatives(), "zero", e.pickBestProbability(), e.pickTop()));
    }

    private static String csv(Experiment.Result result) {
        return ResultsCsv.format(result.reactive(), result.assisted());
    }

    @Test
    void sameSeedGivesTheSameCsv() {
        assertEquals(csv(Experiment.run(small(7, 60))), csv(Experiment.run(small(7, 60))));
        assertNotEquals(csv(Experiment.run(small(7, 60))), csv(Experiment.run(small(8, 60))));
    }

    @Test
    void bothConditionsShareProfilesAndArrivals() {
        Experiment.Result result = Experiment.run(small(11, 60));

        assertEquals(60, result.setup().profiles().size());
        assertEquals(result.setup().profiles(), result.reactive().stream().map(StudentResult::profile).toList());
        assertEquals(result.setup().profiles(), result.assisted().stream().map(StudentResult::profile).toList());
        // Los perfiles y las llegadas salen solo de la semilla.
        assertEquals(result.setup().profiles(), Experiment.prepare(small(11, 60)).profiles());
        assertTrue(result.setup().profiles().stream().anyMatch(p -> p.arrivalSeconds() > 0));
        // Las llegadas de cada ventana van en orden creciente.
        for (int w = 1; w <= 3; w++) {
            final int window = w;
            List<Double> arrivals = result.setup().profiles().stream()
                    .filter(p -> p.window() == window).map(Profile::arrivalSeconds).toList();
            assertEquals(arrivals.stream().sorted().toList(), arrivals);
        }
    }

    @Test
    void assistedNeverClashesWhileReactiveDoes() {
        Experiment.Result result = Experiment.run(small(3, 80));

        assertEquals(0, result.assisted().stream().mapToInt(StudentResult::rTraslape).sum());
        assertTrue(result.reactive().stream().mapToInt(StudentResult::rTraslape).sum() > 0);
        assertTrue(result.assisted().stream().allMatch(r -> r.engineCalls() > 0));
        assertTrue(result.reactive().stream().allMatch(r -> r.engineCalls() == 0));
        assertTrue(result.assisted().stream().allMatch(r -> r.tTotalSeconds() > 0));
    }

    @Test
    void withAmpleSeatsThereAreNoSeatRejections() {
        ExperimentConfig base = small(5, 80);
        ExperimentConfig.OfferParams o = base.offer();
        ExperimentConfig config = base.withOffer(new ExperimentConfig.OfferParams(o.file(), 50, o.maxCredits(),
                o.coursesPerSemester(), o.sharedCoursesPerSemester(), o.sharedUpToSemester(), o.creditsMin(),
                o.creditsMax(), o.minSections(), o.maxSections(), o.targetGroupSize(), 500, o.sectionShift(),
                o.shiftAlignment(), o.popularSeatShare(), o.seatUnevenness()));

        Experiment.Result result = Experiment.run(config);

        assertEquals(0, result.reactive().stream().mapToInt(StudentResult::rCupo).sum());
        assertEquals(0, result.assisted().stream().mapToInt(StudentResult::rCupo).sum());
    }

    @Test
    void platformNeverGoesBelowZero() {
        Section only = new Section(new SectionId("X-01"), new CourseId("X"),
                List.of(TimeSlot.of(Weekday.MONDAY, 360, 480).orElseThrow()), 1);
        Platform platform = new Platform(List.of(new SectionSeats(only, 1)), (s, a) -> { });

        assertEquals(Platform.Outcome.ACCEPTED, platform.submit(0, only));
        assertEquals(Platform.Outcome.FULL, platform.submit(1, only));
        assertEquals(0, platform.available(only.id()));
        platform.verifyInvariants();
        platform.withdraw(0, only.course());
        assertEquals(1, platform.available(only.id()));
        // Una corrida con cupos escasos también termina con los invariantes (se verifican al final).
        ExperimentConfig scarce = small(9, 120);
        Experiment.Result result = Experiment.run(scarce);
        assertTrue(result.setup().offer().totalSeats() > 0);
    }

    // --- Caso construido a mano: la latencia del evento de cupo ---------------------------------

    private static ExperimentConfig handBuilt() {
        ExperimentConfig base = small(1, 0);
        ExperimentConfig.Dist ten = new ExperimentConfig.Dist(10, 0);
        ExperimentConfig.Dist five = new ExperimentConfig.Dist(5, 0);
        return base.withTimes(new ExperimentConfig.TimeParams(five, five, five, five, five, ten, ten, five))
                .withEngine(new ExperimentConfig.EngineParams("forward-checking", 50, 5, "zero", 1.0, 5))
                .withPlatform(new ExperimentConfig.PlatformParams(2.0));
    }

    private static Offer oneCourseTwoSections() {
        CourseId c1 = new CourseId("C1");
        Section s1 = new Section(new SectionId("C1-01"), c1,
                List.of(TimeSlot.of(Weekday.MONDAY, 360, 480).orElseThrow()), 1);
        Section s2 = new Section(new SectionId("C1-02"), c1,
                List.of(TimeSlot.of(Weekday.TUESDAY, 360, 480).orElseThrow()), 5);
        return new Offer(List.of(new SectionSeats(s1, 1), new SectionSeats(s2, 5)),
                List.of(new Course(c1, 3)), new EnrollmentRules(20));
    }

    private static Profile student(int index, double arrival) {
        return new Profile(index, "U" + (index + 1), "SISTEMAS", 1, 1, 1, Shift.MANANA,
                List.of(new CourseId("C1")), arrival);
    }

    @Test
    void engineOffersAFullSectionInsideTheEventLatencyAndTheAlternativeFixesIt() {
        // U1 envía C1-01 en t=15 y toma su único cupo; el evento llega al motor en t=17.
        // U2 consulta en t=16: la réplica aún muestra el cupo, el motor sugiere C1-01 y se llena.
        List<StudentResult> results = Experiment.runCondition(handBuilt(), oneCourseTwoSections(),
                List.of(student(0, 0), student(1, 16)), Condition.ASSISTED);

        StudentResult first = results.get(0);
        assertEquals(0, first.rCupo());
        assertEquals(1, first.coursesEnrolled());

        StudentResult second = results.get(1);
        assertEquals(1, second.rCupo());
        assertEquals(0, second.rTraslape());
        assertEquals(1, second.coursesEnrolled());
        assertEquals(2, second.engineCalls());
        // 10 revisar + 5 enviar (rechazado) + 5 leer + 10 revisar alternativas + 5 enviar + 5 confirmar.
        assertEquals(40.0, second.tTotalSeconds(), 1e-9);
    }

    @Test
    void ifTheEventHasArrivedTheEngineDoesNotOfferTheFullSection() {
        List<StudentResult> results = Experiment.runCondition(handBuilt(), oneCourseTwoSections(),
                List.of(student(0, 0), student(1, 18)), Condition.ASSISTED);

        assertEquals(0, results.get(1).rCupo());
        assertEquals(1, results.get(1).engineCalls());
        assertEquals(1, results.get(1).coursesEnrolled());
    }

    @Test
    void reactiveStudentGetsRejectedForSeatsWhenTheOtherTakesTheLastOneFirst() {
        // Ambos cargan la página en t=0 y ven un cupo en C1-01, el único de su jornada (mañana):
        // el primero en enviar se lo queda y el otro recibe un rechazo por cupo.
        CourseId c1 = new CourseId("C1");
        Section s1 = new Section(new SectionId("C1-01"), c1,
                List.of(TimeSlot.of(Weekday.MONDAY, 360, 480).orElseThrow()), 1);
        Section s2 = new Section(new SectionId("C1-02"), c1,
                List.of(TimeSlot.of(Weekday.TUESDAY, 1080, 1200).orElseThrow()), 5);
        Offer offer = new Offer(List.of(new SectionSeats(s1, 1), new SectionSeats(s2, 5)),
                List.of(new Course(c1, 3)), new EnrollmentRules(20));
        ExperimentConfig config = handBuilt().withBehavior(new ExperimentConfig.BehaviorParams(
                50, 0, 0, 2, 60, 10, 2));

        List<StudentResult> results = Experiment.runCondition(config, offer,
                List.of(student(0, 0), student(1, 0)), Condition.REACTIVE);

        assertEquals(0, results.get(0).rCupo());
        assertEquals(1, results.get(1).rCupo());
        assertEquals(0, results.stream().mapToInt(StudentResult::rTraslape).sum());
        assertEquals(1, results.get(1).coursesEnrolled());
    }

    // --- Configuración, CSV y calibración -------------------------------------------------------

    @Test
    void baseConfigMatchesTheDefaults() {
        assertEquals(ExperimentConfig.defaults(), ExperimentConfig.load(BASE));
    }

    @Test
    void unknownConfigKeysAreRefused(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("mala.json");
        Files.writeString(file, "{ \"seedd\": 1 }");
        assertThrows(RuntimeException.class, () -> ExperimentConfig.load(file));

        Files.writeString(file, "{ \"seed\": 5, \"engine\": { \"maxResults\": 7 } }");
        ExperimentConfig partial = ExperimentConfig.load(file);
        assertEquals(5, partial.seed());
        assertEquals(7, partial.engine().maxResults());
        assertEquals(5, partial.engine().maxAlternatives());
    }

    @Test
    void csvHasOneRowPerProfileAndConditionAndRoundTrips(@TempDir Path dir) {
        Experiment.Result result = Experiment.run(small(2, 30));
        Path file = dir.resolve("results.csv");
        ResultsCsv.write(file, result.reactive(), result.assisted());

        List<ResultRow> rows = ResultsCsv.read(file);
        assertEquals(60, rows.size());
        assertEquals(30, rows.stream().filter(r -> r.condition().equals("A")).count());
        assertTrue(csv(result).startsWith("profile_id,program,semester,n_courses,window,preference,condition,"
                + "t_total_s,r_traslape,r_cupo,r_total,courses_enrolled,courses_deferred,engine_calls,"
                + "engine_ms_total"));
        assertEquals(result.reactive().getFirst().toRow().rTraslape(), rows.getFirst().rTraslape());
    }

    @Test
    void calibrationReportBinsTheReactiveRows() {
        Calibration calibration = Calibration.load("");
        List<ResultRow> rows = List.of(
                new ResultRow("a", "A", 600, 0, 0, 0, 0),         // <15 min, 0 rehechas, no
                new ResultRow("b", "A", 1800, 1, 1, 1, 0),        // 15-30 (30 incluido), 1, sí
                new ResultRow("c", "A", 3601, 7, 0, 0, 1),        // 1-2 h, 5 o más, casi
                new ResultRow("d", "A", 8000, 4, 2, 0, 0),        // más de 2 h, 4, no (A13b: sí)
                new ResultRow("e", "B", 10, 0, 0, 0, 0));         // la condición B no cuenta

        List<CalibrationReport.Comparison> comparisons = CalibrationReport.compare(rows, calibration);

        double[] a9 = comparisons.get(0).simulated();
        assertEquals(25, a9[0], 1e-9);
        assertEquals(25, a9[1], 1e-9);
        assertEquals(25, a9[4], 1e-9);
        assertEquals(25, a9[5], 1e-9);
        double[] a11 = comparisons.get(1).simulated();
        assertEquals(25, a11[0], 1e-9);
        assertEquals(25, a11[1], 1e-9);
        assertEquals(0, a11[2], 1e-9);
        assertEquals(25, a11[3], 1e-9);
        assertEquals(25, a11[4], 1e-9);
        assertEquals(List.of(25.0, 25.0, 50.0), java.util.Arrays.stream(comparisons.get(2).simulated()).boxed().toList());
        assertEquals(List.of(50.0, 25.0, 25.0), java.util.Arrays.stream(comparisons.get(3).simulated()).boxed().toList());
        assertFalse(CalibrationReport.format(comparisons, 4).isBlank());
    }

    @Test
    void anExternalOfferCanReplaceTheGeneratedOne() {
        ExperimentConfig base = small(4, 20);
        ExperimentConfig.OfferParams o = base.offer();
        ExperimentConfig config = base.withOffer(new ExperimentConfig.OfferParams("fixtures/sample.json", o.slackFactor(),
                o.maxCredits(), o.coursesPerSemester(), o.sharedCoursesPerSemester(), o.sharedUpToSemester(),
                o.creditsMin(), o.creditsMax(), o.minSections(), o.maxSections(), o.targetGroupSize(),
                o.minSeatsPerSection(), o.sectionShift(), o.shiftAlignment(), o.popularSeatShare(),
                o.seatUnevenness()));

        Experiment.Result result = Experiment.run(config);

        assertEquals(7, result.setup().offer().sections().size());
        assertEquals(0, result.assisted().stream().mapToInt(StudentResult::rTraslape).sum());
    }
}
