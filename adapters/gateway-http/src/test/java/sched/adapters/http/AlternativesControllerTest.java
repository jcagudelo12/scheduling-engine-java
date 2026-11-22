package sched.adapters.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import sched.application.AppException;
import sched.application.ApplyLogEntry;
import sched.application.CatalogLogEntry;
import sched.application.CatalogReplica;
import sched.application.SuggestAlternatives;
import sched.domain.Course;
import sched.domain.CourseId;
import sched.domain.EnrollmentRules;
import sched.domain.Section;
import sched.domain.SectionId;
import sched.domain.SectionSeats;
import sched.domain.TimeSlot;
import sched.domain.Weekday;
import sched.solver.ForwardCheckingSolver;

/** Llama al controlador directamente, sin levantar Spring. */
class AlternativesControllerTest {

    private ApplyLogEntry replicaLog;
    private AlternativesController controller;

    @BeforeEach
    void engine() {
        CatalogReplica replica = new CatalogReplica();
        replicaLog = new ApplyLogEntry(replica);
        controller = new AlternativesController(new SuggestAlternatives(new ForwardCheckingSolver(), replica, 5));
    }

    private static SectionSeats group(String id, String course, Weekday day, int seats) {
        return new SectionSeats(new Section(new SectionId(id), new CourseId(course),
                List.of(TimeSlot.of(day, 420, 540).orElseThrow()), 30), seats);
    }

    private void load(int maxCredits) throws AppException {
        replicaLog.execute(new CatalogLogEntry.Snapshot(7, List.of(
                group("MAT-01", "MAT", Weekday.MONDAY, 0),
                group("MAT-02", "MAT", Weekday.WEDNESDAY, 5),
                group("FIS-01", "FIS", Weekday.TUESDAY, 5)),
                List.of(new Course(new CourseId("MAT"), 4), new Course(new CourseId("FIS"), 3)),
                new EnrollmentRules(maxCredits)));
    }

    private static AlternativesController.AlternativesRequestDto request(Map<String, String> selection) {
        return request(selection, null);
    }

    private static AlternativesController.AlternativesRequestDto request(
            Map<String, String> selection, List<String> enrolled) {
        return new AlternativesController.AlternativesRequestDto("s1", List.of("MAT", "FIS"), null, selection, enrolled);
    }

    @Test
    void enrolledGroupWithoutSeatsIsKeptAsAvailable() throws Exception {
        load(0); // MAT-01 tiene 0 cupos: el último lo tomó el propio estudiante

        var response = controller.alternatives(
                request(Map.of("MAT", "MAT-01", "FIS", "FIS-01"), List.of("MAT-01")));

        assertTrue(response.selectionValid());
        assertTrue(response.conflicts().withoutSeats().isEmpty());
        assertEquals(0, response.alternatives().getFirst().distance());
        assertEquals(List.of("MAT-01", "FIS-01"),
                response.alternatives().getFirst().sections().stream().map(s -> s.sectionId()).toList());
    }

    @Test
    void enrolledGroupOutsideTheSelectionAnswers400() throws Exception {
        load(0);

        AppException.InvalidSelection error = assertThrows(AppException.InvalidSelection.class,
                () -> controller.alternatives(request(Map.of("FIS", "FIS-01"), List.of("MAT-01"))));
        assertEquals(HttpStatus.BAD_REQUEST, controller.handleInvalidSelection(error).getStatusCode());
    }

    @Test
    void blankEnrolledGroupAnswers400() throws Exception {
        load(0);

        var error = assertThrows(CombinationsController.InvalidRequestException.class,
                () -> controller.alternatives(request(Map.of("MAT", "MAT-01"), List.of(" "))));
        assertEquals(HttpStatus.BAD_REQUEST, controller.handleInvalidRequest(error).getStatusCode());
    }

    @Test
    void parsesTheSelectionAndShapesTheResponse() throws Exception {
        load(0);

        var response = controller.alternatives(request(Map.of("MAT", "MAT-01", "FIS", "FIS-01")));

        assertEquals(7, response.catalogSeq());
        assertTrue(response.complete());
        assertEquals(false, response.selectionValid());
        assertEquals(List.of(new AlternativesController.ChoiceDto("MAT", "MAT-01")),
                response.conflicts().withoutSeats());
        assertTrue(response.conflicts().clashes().isEmpty());
        var best = response.alternatives().getFirst();
        assertEquals(1, best.distance());
        assertEquals(0, best.penalty());
        assertEquals(List.of(new AlternativesController.ChangeDto("MAT", "MAT-01", "MAT-02", "no_seats")),
                best.changes());
        assertEquals(List.of("MAT-02", "FIS-01"), best.sections().stream().map(s -> s.sectionId()).toList());
    }

    @Test
    void missingSelectionMeansNoChoices() throws Exception {
        load(0);

        var response = controller.alternatives(request(null));

        var first = response.alternatives().getFirst();
        assertEquals(0, first.distance());
        assertEquals(2, first.changes().size());
        assertNull(first.changes().getFirst().from());
        assertEquals("not_selected", first.changes().getFirst().reason());
    }

    @Test
    void selectionForAnUnrequestedCourseAnswers400() throws Exception {
        load(0);

        AppException.InvalidSelection error = assertThrows(AppException.InvalidSelection.class,
                () -> controller.alternatives(request(Map.of("QUI", "QUI-01"))));

        var answer = controller.handleInvalidSelection(error);
        assertEquals(HttpStatus.BAD_REQUEST, answer.getStatusCode());
        assertEquals("invalid_request", answer.getBody().error());
    }

    @Test
    void blankSectionInTheSelectionAnswers400() throws Exception {
        load(0);

        var error = assertThrows(CombinationsController.InvalidRequestException.class,
                () -> controller.alternatives(request(Map.of("MAT", " "))));
        assertEquals(HttpStatus.BAD_REQUEST, controller.handleInvalidRequest(error).getStatusCode());
    }

    @Test
    void exceedingTheLimitAnswers422() throws Exception {
        load(6);

        var error = assertThrows(AppException.CreditLimitExceeded.class,
                () -> controller.alternatives(request(Map.of("MAT", "MAT-02"))));
        var answer = controller.handleCreditLimit(error);
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, answer.getStatusCode());
        assertEquals(7, answer.getBody().totalCredits());
    }

    @Test
    void catalogNotLoadedAnswers503() {
        var error = assertThrows(AppException.CatalogUnavailable.class,
                () -> controller.alternatives(request(Map.of())));
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, controller.handle(error).getStatusCode());
    }
}
