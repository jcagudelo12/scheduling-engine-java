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
                e.solver(), e.maxResults(), e.maxAlternatives(), "zero", e.pickBestProbability(), e.pickTop(),
                e.usePreferences()));
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
                o.shiftAlignment(), o.popularSeatShare(), o.seatUnevenness(), o.extraBlockProbability(), o.popularitySkew()));

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
        // browse, choose, submit, read, withdraw, confirm, reviewCombination, reviewAlternative
        return base.withTimes(new ExperimentConfig.TimeParams(five, five, five, five, five, five, ten, ten))
                .withEngine(new ExperimentConfig.EngineParams("forward-checking", 50, 5, "zero", 1.0, 5, true))
                .withPlatform(new ExperimentConfig.PlatformParams(2.0))
                // Sin heterogeneidad entre estudiantes (velocidad igual para todos): tiempos exactos.
                .withBehavior(new ExperimentConfig.BehaviorParams(2.0, 0.15, 0, 0.9, 4.0, 0, 0.31, 2, 60, 10, 2, 0, 5, 3, 1.0));
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
        // U1: revisa 2 combinaciones (20 s), navega y envía (10 s) y toma el único cupo de C1-01 en t=30;
        // el evento llega al motor en t=32. U2 consulta en t=31: la réplica aún muestra el cupo, el
        // motor sugiere C1-01 primero y se llena.
        List<StudentResult> results = Experiment.runCondition(handBuilt(), oneCourseTwoSections(),
                List.of(student(0, 0), student(1, 31)), Condition.ASSISTED);

        StudentResult first = results.get(0);
        assertEquals(0, first.rCupo());
        assertEquals(1, first.coursesEnrolled());

        StudentResult second = results.get(1);
        assertEquals(1, second.rCupo());
        assertEquals(0, second.rTraslape());
        assertEquals(1, second.coursesEnrolled());
        assertEquals(2, second.engineCalls());
        // 20 revisar (2 combinaciones) + 5 navegar + 5 enviar (rechazado) + 5 leer + 10 revisar
        // (1 alternativa) + 5 navegar + 5 enviar + 5 confirmar: B paga navegar y enviar cada grupo.
        assertEquals(60.0, second.tTotalSeconds(), 1e-9);
    }

    @Test
    void ifTheEventHasArrivedTheEngineDoesNotOfferTheFullSection() {
        List<StudentResult> results = Experiment.runCondition(handBuilt(), oneCourseTwoSections(),
                List.of(student(0, 0), student(1, 33)), Condition.ASSISTED);

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
                50, 0, 0, 0, 1, 0, 0, 2, 60, 10, 2, 0, 5, 3, 1.0));

        List<StudentResult> results = Experiment.runCondition(config, offer,
                List.of(student(0, 0), student(1, 0)), Condition.REACTIVE);

        assertEquals(0, results.get(0).rCupo());
        assertEquals(1, results.get(1).rCupo());
        assertEquals(0, results.stream().mapToInt(StudentResult::rTraslape).sum());
        assertEquals(1, results.get(1).coursesEnrolled());
    }

    // --- Regla de abandono de B ----------------------------------------------------------------

    private static Section sec(String id, String course, Weekday day, int capacity) {
        return new Section(new SectionId(id), new CourseId(course),
                List.of(TimeSlot.of(day, 360, 480).orElseThrow()), capacity);
    }

    private static Profile threeCourses() {
        return new Profile(0, "U1", "SISTEMAS", 1, 3, 1, Shift.MANANA,
                List.of(new CourseId("C1"), new CourseId("C2"), new CourseId("C3")), 0);
    }

    @Test
    void assistedDropsTheImpossibleCourseNotTheLastOne() {
        // C2 no tiene ningún cupo; C3, la última, es compatible con todo: se quita C2.
        Section c1 = sec("C1-01", "C1", Weekday.MONDAY, 5);
        Section c2 = sec("C2-01", "C2", Weekday.TUESDAY, 5);
        Section c3 = sec("C3-01", "C3", Weekday.WEDNESDAY, 5);
        Offer offer = new Offer(List.of(new SectionSeats(c1, 5), new SectionSeats(c2, 0), new SectionSeats(c3, 5)),
                List.of(new Course(new CourseId("C1"), 3), new Course(new CourseId("C2"), 3),
                        new Course(new CourseId("C3"), 3)), new EnrollmentRules(20));

        StudentResult r = Experiment.runCondition(handBuilt(), offer, List.of(threeCourses()), Condition.ASSISTED)
                .getFirst();

        assertEquals(2, r.coursesEnrolled());
        assertEquals(1, r.coursesDeferred());
        assertEquals(0, r.rTraslape());
    }

    @Test
    void assistedDropsTheCourseThatMakesTheCombinationPossible() {
        // C1 y C2 solo tienen un grupo y se cruzan; C3, la última, es compatible: quitarla no
        // ayuda, así que se quita la de menor prioridad que sí lo logra (C2) y C3 se conserva.
        Section c1 = sec("C1-01", "C1", Weekday.MONDAY, 5);
        Section c2 = sec("C2-01", "C2", Weekday.MONDAY, 5);
        Section c3 = sec("C3-01", "C3", Weekday.WEDNESDAY, 5);
        Offer offer = new Offer(List.of(new SectionSeats(c1, 5), new SectionSeats(c2, 5), new SectionSeats(c3, 5)),
                List.of(new Course(new CourseId("C1"), 3), new Course(new CourseId("C2"), 3),
                        new Course(new CourseId("C3"), 3)), new EnrollmentRules(20));

        StudentResult r = Experiment.runCondition(handBuilt(), offer, List.of(threeCourses()), Condition.ASSISTED)
                .getFirst();

        assertEquals(2, r.coursesEnrolled());
        assertEquals(1, r.coursesDeferred());
        // Combinaciones con todo (vacía), con C3 menos (vacía) y con C2 menos (hay).
        assertEquals(3, r.engineCalls());
    }

    @Test
    void ownLastSeatDoesNotMakeTheEngineChangeTheGroup() {
        // El estudiante toma el último cupo de C1-01; otro estudiante hace fallar a U1 en C2-01 y U1
        // pide alternativas: C1-01 (inscrito, 0 cupos en la réplica) debe conservarse.
        Section c1 = sec("C1-01", "C1", Weekday.MONDAY, 1);
        Section c2a = sec("C2-01", "C2", Weekday.TUESDAY, 1);
        Section c2b = sec("C2-02", "C2", Weekday.THURSDAY, 5);
        Offer offer = new Offer(List.of(new SectionSeats(c1, 1), new SectionSeats(c2a, 1), new SectionSeats(c2b, 5)),
                List.of(new Course(new CourseId("C1"), 3), new Course(new CourseId("C2"), 3)),
                new EnrollmentRules(20));
        Profile u1 = new Profile(0, "U1", "SISTEMAS", 1, 2, 1, Shift.MANANA,
                List.of(new CourseId("C1"), new CourseId("C2")), 0);
        Profile u2 = new Profile(1, "U2", "SISTEMAS", 1, 1, 1, Shift.MANANA, List.of(new CourseId("C2")), 0);

        List<StudentResult> results = Experiment.runCondition(handBuilt(), offer, List.of(u1, u2), Condition.ASSISTED);

        assertEquals(1, results.get(0).rCupo());
        assertEquals(2, results.get(0).coursesEnrolled());
        assertEquals(0, results.get(0).withdrawals());
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
                o.seatUnevenness(), o.extraBlockProbability(), o.popularitySkew()));

        Experiment.Result result = Experiment.run(config);

        assertEquals(7, result.setup().offer().sections().size());
        assertEquals(0, result.assisted().stream().mapToInt(StudentResult::rTraslape).sum());
    }

    @Test
    void equivalentActionsCostTheSameInAAndB() {
        // Una asignatura con un solo grupo, sin competencia. A: navegar 5 + elegir 5 + enviar 5 + confirmar 5.
        // B: revisar 1 combinación 10 + navegar 5 + enviar 5 + confirmar 5. Navegar, enviar y confirmar
        // cuestan igual; solo difieren elegir (A) y revisar la combinación (B).
        CourseId c1 = new CourseId("C1");
        Section only = new Section(new SectionId("C1-01"), c1,
                List.of(TimeSlot.of(Weekday.MONDAY, 360, 480).orElseThrow()), 5);
        Offer offer = new Offer(List.of(new SectionSeats(only, 5)), List.of(new Course(c1, 3)), new EnrollmentRules(20));

        StudentResult a = Experiment.runCondition(handBuilt(), offer, List.of(student(0, 0)), Condition.REACTIVE).getFirst();
        StudentResult b = Experiment.runCondition(handBuilt(), offer, List.of(student(0, 0)), Condition.ASSISTED).getFirst();

        assertEquals(20.0, a.tTotalSeconds(), 1e-9);
        assertEquals(25.0, b.tTotalSeconds(), 1e-9);
    }

    @Test
    void studentTraitsAreHeterogeneousButIdenticalForTheSameStudent() {
        ExperimentConfig config = ExperimentConfig.defaults();
        double[] awareness = new double[400];
        double[] speed = new double[400];
        for (int i = 0; i < awareness.length; i++) {
            StudentTraits t = StudentTraits.draw(config, i);
            assertEquals(t, StudentTraits.draw(config, i));
            awareness[i] = t.awareness();
            speed[i] = t.speed();
        }
        double high = java.util.Arrays.stream(awareness).filter(x -> x > 0.6).count() / (double) awareness.length;
        assertTrue(high > 0.04 && high < 0.25, "planificadores: " + high);
        assertTrue(java.util.Arrays.stream(awareness).max().orElse(0) > java.util.Arrays.stream(awareness).average().orElse(0) + 0.3);
        assertEquals(1.0, java.util.Arrays.stream(speed).average().orElse(0), 0.15);
        assertTrue(java.util.Arrays.stream(speed).max().orElse(0) > 1.5);
    }

    @Test
    void valuesCanBeSetByPath() {
        ExperimentConfig config = ExperimentConfig.defaults().withValues(java.util.Map.of(
                "times.browseCourse.meanSeconds", 99.0, "behavior.nearLossSeats", 3.4));
        assertEquals(99.0, config.times().browseCourse().meanSeconds(), 0);
        assertEquals(3, config.behavior().nearLossSeats());
        assertEquals(ExperimentConfig.defaults().times().confirm(), config.times().confirm());
        assertThrows(IllegalArgumentException.class, () -> config.withValues(java.util.Map.of("times.nope", 1.0)));
    }
}
