package sched.adapters.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import sched.application.AppException;
import sched.application.ApplyLogEntry;
import sched.application.CatalogLogEntry;
import sched.application.CatalogReplica;
import sched.application.GenerateCombinations;
import sched.domain.Course;
import sched.domain.CourseId;
import sched.domain.EnrollmentRules;
import sched.domain.Section;
import sched.domain.SectionId;
import sched.domain.Schedule;
import sched.domain.SectionSeats;
import sched.domain.TimeSlot;
import sched.domain.Weekday;
import sched.solver.Scored;
import sched.solver.Solver;

/** Llama al controlador directamente, sin levantar Spring: no hay tráfico HTTP real. */
class CombinationsControllerTest {

    private static final CombinationsController.StudentContextDto REQUEST =
            new CombinationsController.StudentContextDto("s1", List.of("MAT", "FIS"), null);

    private final AtomicInteger searches = new AtomicInteger();
    private final List<sched.solver.Problem> problems = new ArrayList<>();
    private ApplyLogEntry replicaLog;
    private CombinationsController controller;

    @BeforeEach
    void engine() {
        CatalogReplica replica = new CatalogReplica();
        replicaLog = new ApplyLogEntry(replica);
        Solver solver = problem -> {
            searches.incrementAndGet();
            problems.add(problem);
            // Una combinación con el único grupo cargado y costo según las preferencias.
            return problem.candidates().isEmpty() ? List.of()
                    : List.of(new Scored(new Schedule(List.of(problem.candidates().getFirst())),
                            problem.cost().of(problem.candidates().getFirst())));
        };
        controller = new CombinationsController(new GenerateCombinations(solver, replica, 10));
    }

    private void load(int maxCredits) throws AppException {
        Section mat = new Section(new SectionId("MAT-01"), new CourseId("MAT"),
                List.of(TimeSlot.of(Weekday.MONDAY, 420, 540).orElseThrow()), 30);
        replicaLog.execute(new CatalogLogEntry.Snapshot(1, List.of(new SectionSeats(mat, 5)),
                List.of(new Course(new CourseId("MAT"), 4), new Course(new CourseId("FIS"), 3)),
                new EnrollmentRules(maxCredits)));
    }

    @Test
    void responseIncludesTotalAndMaxCredits() throws Exception {
        load(10);

        var response = controller.combinations(REQUEST);
        assertEquals(7, response.totalCredits());
        assertEquals(10, response.maxCredits());
        assertEquals(1, searches.get());
    }

    @Test
    void withoutLimitMaxCreditsIsZero() throws Exception {
        load(0);

        assertEquals(0, controller.combinations(REQUEST).maxCredits());
    }

    @Test
    void exceedingTheLimitSkipsTheSearchAndAnswers422() throws Exception {
        load(6);

        AppException.CreditLimitExceeded error =
                assertThrows(AppException.CreditLimitExceeded.class, () -> controller.combinations(REQUEST));
        assertEquals(0, searches.get());

        var answer = controller.handleCreditLimit(error);
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, answer.getStatusCode());
        assertEquals(new CombinationsController.CreditLimitErrorDto("credit_limit_exceeded", 7, 6), answer.getBody());
    }

    private static CombinationsController.StudentContextDto avoiding(CombinationsController.SlotDto... windows) {
        return new CombinationsController.StudentContextDto("s1", List.of("MAT", "FIS"), List.of(windows));
    }

    @Test
    void avoidWindowsBecomeTheSolverCostAndPenaltyIsReturned() throws Exception {
        load(0);

        var response = controller.combinations(avoiding(
                new CombinationsController.SlotDto("Monday", 480, 500)));
        assertEquals(1, response.schedules().size());
        assertEquals(1, response.schedules().getFirst().penalty());
        assertEquals(1, problems.getFirst().cost().of(problems.getFirst().candidates().getFirst()));
    }

    @Test
    void withoutAvoidThePenaltyIsZero() throws Exception {
        load(0);

        assertEquals(0, controller.combinations(REQUEST).schedules().getFirst().penalty());
    }

    @Test
    void invalidAvoidWindowsAnswer400WithoutSearching() throws Exception {
        load(0);

        for (var window : List.of(
                new CombinationsController.SlotDto("monday", 600, 600),
                new CombinationsController.SlotDto("monday", 700, 600),
                new CombinationsController.SlotDto("monday", 0, 1500),
                new CombinationsController.SlotDto("lunes", 420, 540),
                new CombinationsController.SlotDto(null, 420, 540))) {
            var error = assertThrows(CombinationsController.InvalidRequestException.class,
                    () -> controller.combinations(avoiding(window)));
            var answer = controller.handleInvalidRequest(error);
            assertEquals(HttpStatus.BAD_REQUEST, answer.getStatusCode());
            assertEquals("invalid_request", answer.getBody().error());
            assertTrue(answer.getBody().message().startsWith("avoid[0]"));
        }
        assertEquals(0, searches.get());
    }
}
