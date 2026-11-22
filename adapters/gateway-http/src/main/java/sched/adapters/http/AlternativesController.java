package sched.adapters.http;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import sched.adapters.http.CombinationsController.CreditLimitErrorDto;
import sched.adapters.http.CombinationsController.InvalidRequestDto;
import sched.adapters.http.CombinationsController.InvalidRequestException;
import sched.adapters.http.CombinationsController.SectionDto;
import sched.adapters.http.CombinationsController.SlotDto;
import sched.application.Alternatives;
import sched.application.AppException;
import sched.application.SuggestAlternatives;
import sched.domain.CourseId;
import sched.domain.Preferences;
import sched.domain.SectionId;
import sched.domain.StudentContext;
import sched.domain.StudentId;

/**
 * {@code POST /alternatives}: alternativas a la selección actual del estudiante, que conservan
 * la mayor cantidad posible de sus elecciones. Mismo contexto de estudiante que
 * {@code /combinations} más {@code selection}.
 *
 * <p>Provisional: el contexto llega sin firmar (ADR 0004). JSON en snake_case.
 */
@RestController
public class AlternativesController {

    /**
     * Contexto del estudiante y selección actual: objeto {@code curso -> grupo}. Es opcional (nulo
     * equivale a sin elecciones) y puede no cubrir todos los cursos pedidos.
     */
    public record AlternativesRequestDto(
            String studentId,
            List<String> eligibleCourses,
            List<SlotDto> avoid,
            Map<String, String> selection) {
    }

    public record AlternativesResponse(
            long catalogSeq,
            boolean stale,
            int totalCredits,
            int maxCredits,
            boolean selectionValid,
            ConflictsDto conflicts,
            List<AlternativeDto> alternatives) {
    }

    public record ChoiceDto(String course, String section) {
    }

    public record ClashDto(ChoiceDto first, ChoiceDto second) {
    }

    public record ConflictsDto(List<ChoiceDto> withoutSeats, List<ClashDto> clashes) {
    }

    /** {@code from} es nulo cuando el curso no tenía elección; {@code reason} en minúsculas. */
    public record ChangeDto(String course, String from, String to, String reason) {
    }

    public record AlternativeDto(
            int distance, long penalty, List<ChangeDto> changes, List<SectionDto> sections) {
    }

    private final SuggestAlternatives suggest;

    public AlternativesController(SuggestAlternatives suggest) {
        this.suggest = suggest;
    }

    @PostMapping("/alternatives")
    public AlternativesResponse alternatives(@RequestBody AlternativesRequestDto request) throws AppException {
        if (request.eligibleCourses() == null) {
            throw new InvalidRequestException("falta eligible_courses");
        }
        StudentContext student = new StudentContext(
                new StudentId(request.studentId()),
                request.eligibleCourses().stream().map(CourseId::new).toList(),
                new Preferences(CombinationsController.parseAvoid(request.avoid())));
        Alternatives result = suggest.execute(student, parseSelection(request.selection()));
        return new AlternativesResponse(
                result.catalogSeq(),
                result.stale(),
                result.totalCredits(),
                result.maxCredits(),
                result.selectionValid(),
                new ConflictsDto(
                        result.conflicts().withoutSeats().stream().map(AlternativesController::toDto).toList(),
                        result.conflicts().clashes().stream()
                                .map(c -> new ClashDto(toDto(c.first()), toDto(c.second())))
                                .toList()),
                result.alternatives().stream().map(AlternativesController::toDto).toList());
    }

    @ExceptionHandler(InvalidRequestException.class)
    public ResponseEntity<InvalidRequestDto> handleInvalidRequest(InvalidRequestException error) {
        return HttpErrors.invalidRequest(error.getMessage());
    }

    /** Selección con un curso no pedido o un grupo de otro curso: 400. */
    @ExceptionHandler(AppException.InvalidSelection.class)
    public ResponseEntity<InvalidRequestDto> handleInvalidSelection(AppException.InvalidSelection error) {
        return HttpErrors.invalidRequest(error.getMessage());
    }

    @ExceptionHandler(AppException.CreditLimitExceeded.class)
    public ResponseEntity<CreditLimitErrorDto> handleCreditLimit(AppException.CreditLimitExceeded error) {
        return HttpErrors.creditLimit(error);
    }

    @ExceptionHandler(AppException.class)
    public ResponseEntity<String> handle(AppException error) {
        return HttpErrors.general(error);
    }

    private static Map<CourseId, SectionId> parseSelection(Map<String, String> selection) {
        Map<CourseId, SectionId> parsed = new HashMap<>();
        if (selection == null) {
            return parsed;
        }
        selection.forEach((course, section) -> {
            if (course == null || section == null || section.isBlank()) {
                throw new InvalidRequestException("selection[" + course + "]: falta el grupo");
            }
            parsed.put(new CourseId(course), new SectionId(section));
        });
        return parsed;
    }

    private static ChoiceDto toDto(Alternatives.Choice choice) {
        return new ChoiceDto(choice.course().value(), choice.section().value());
    }

    private static AlternativeDto toDto(Alternatives.Alternative alternative) {
        return new AlternativeDto(
                alternative.distance(),
                alternative.penalty(),
                alternative.changes().stream()
                        .map(c -> new ChangeDto(
                                c.course().value(),
                                c.from() == null ? null : c.from().value(),
                                c.to().value(),
                                c.reason().name().toLowerCase()))
                        .toList(),
                alternative.schedule().sections().stream()
                        .map(s -> new SectionDto(
                                s.id().value(),
                                s.course().value(),
                                s.slots().stream()
                                        .map(slot -> new SlotDto(
                                                slot.day().name().toLowerCase(), slot.start(), slot.end()))
                                        .toList()))
                        .toList());
    }
}
