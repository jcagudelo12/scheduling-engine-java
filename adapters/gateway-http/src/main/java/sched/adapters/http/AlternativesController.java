package sched.adapters.http;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
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
 * <p>En modo {@code jwt} el estudiante y sus cursos salen del token firmado (ADR 0004); preferencias,
 * {@code selection} y {@code enrolled} siguen en el cuerpo. JSON en snake_case.
 */
@RestController
public class AlternativesController {

    /**
     * Contexto del estudiante y selección actual: objeto {@code curso -> grupo}. Es opcional (nulo
     * equivale a sin elecciones) y puede no cubrir todos los cursos pedidos. {@code enrolled}
     * (opcional): grupos de la selección que el estudiante ya tiene inscritos; cuentan como con
     * cupo aunque la réplica marque 0.
     */
    public record AlternativesRequestDto(
            String studentId,
            List<String> eligibleCourses,
            List<SlotDto> avoid,
            Map<String, String> selection,
            List<String> enrolled) {
    }

    public record AlternativesResponse(
            long catalogSeq,
            boolean stale,
            int totalCredits,
            int maxCredits,
            boolean selectionValid,
            ConflictsDto conflicts,
            List<AlternativeDto> alternatives,
            boolean complete) {
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
    private final RequestLimits limits;

    /** Con los límites por defecto de {@link RequestLimits#DEFAULTS}. */
    public AlternativesController(SuggestAlternatives suggest) {
        this(suggest, RequestLimits.DEFAULTS);
    }

    public AlternativesController(SuggestAlternatives suggest, RequestLimits limits) {
        this.suggest = suggest;
        this.limits = limits;
    }

    /** Sin token verificado: el contexto sale del cuerpo. */
    public AlternativesResponse alternatives(AlternativesRequestDto request) throws AppException {
        return alternatives(request, null);
    }

    @PostMapping("/alternatives")
    public AlternativesResponse alternatives(
            @RequestBody AlternativesRequestDto request,
            @RequestAttribute(name = VerifiedStudent.ATTRIBUTE, required = false) VerifiedStudent verified)
            throws AppException {
        if (request == null) {
            throw new InvalidRequestException("falta el cuerpo de la solicitud");
        }
        StudentContext student = new StudentContext(
                new StudentId(RequestIdentity.studentId(request.studentId(), verified, limits)),
                RequestIdentity.eligibleCourses(request.eligibleCourses(), verified, limits)
                        .stream().map(CourseId::new).toList(),
                new Preferences(CombinationsController.parseAvoid(request.avoid(), limits)));
        Alternatives result = suggest.execute(student, parseSelection(request.selection()),
                parseEnrolled(request.enrolled()));
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
                result.alternatives().stream().map(AlternativesController::toDto).toList(),
                result.complete());
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

    private Map<CourseId, SectionId> parseSelection(Map<String, String> selection) {
        Map<CourseId, SectionId> parsed = new HashMap<>();
        RequestValidation.optionalIdMap("selection", selection, limits.maxCourses(), limits)
                .forEach((course, section) -> parsed.put(new CourseId(course), new SectionId(section)));
        return parsed;
    }

    private Set<SectionId> parseEnrolled(List<String> enrolled) {
        Set<SectionId> parsed = new HashSet<>();
        for (String section : RequestValidation.optionalIdList("enrolled", enrolled, limits.maxCourses(), limits)) {
            parsed.add(new SectionId(section));
        }
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
