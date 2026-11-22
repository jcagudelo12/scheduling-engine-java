package sched.adapters.http;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import sched.application.AppException;
import sched.application.Combinations;
import sched.application.GenerateCombinations;
import sched.application.PortException;
import sched.application.ScoredSchedule;
import sched.domain.CourseId;
import sched.domain.Preferences;
import sched.domain.Schedule;
import sched.domain.StudentContext;
import sched.domain.StudentId;
import sched.domain.TimeSlot;
import sched.domain.Weekday;

/**
 * {@code POST /combinations}, con el contexto del estudiante en el cuerpo.
 *
 * <p>Provisional: el contexto llega sin firmar. Debe reemplazarse por el token firmado por la
 * institución (ADR 0004) antes de exponer este endpoint.
 *
 * <p>Los nombres JSON van en snake_case (configurado en el servidor).
 */
@RestController
public class CombinationsController {

    /** {@code avoid} es opcional: ventanas que el estudiante prefiere evitar (restricción suave). */
    public record StudentContextDto(String studentId, List<String> eligibleCourses, List<SlotDto> avoid) {
    }

    /** {@code maxCredits} es 0 cuando el periodo no tiene tope de créditos. */
    public record CombinationsResponse(
            long catalogSeq, boolean stale, int totalCredits, int maxCredits, List<ScheduleDto> schedules) {
    }

    /** Cuerpo del 422 cuando los cursos pedidos superan el tope de créditos. */
    public record CreditLimitErrorDto(String error, int totalCredits, int maxCredits) {
    }

    public record ScheduleDto(long penalty, List<SectionDto> sections) {
    }

    public record SectionDto(String sectionId, String courseId, List<SlotDto> slots) {
    }

    public record SlotDto(String day, int startMinute, int endMinute) {
    }

    /** Cuerpo del 400 cuando una ventana de {@code avoid} no es válida. */
    public record InvalidRequestDto(String error, String message) {
    }

    /** Solicitud con datos inválidos; se responde 400. */
    static final class InvalidRequestException extends RuntimeException {
        InvalidRequestException(String message) {
            super(message);
        }
    }

    private final GenerateCombinations generate;

    public CombinationsController(GenerateCombinations generate) {
        this.generate = generate;
    }

    @PostMapping("/combinations")
    public CombinationsResponse combinations(@RequestBody StudentContextDto request) throws AppException {
        StudentContext student = new StudentContext(
                new StudentId(request.studentId()),
                request.eligibleCourses().stream().map(CourseId::new).toList(),
                new Preferences(parseAvoid(request.avoid())));
        Combinations result = generate.execute(student);
        return new CombinationsResponse(
                result.catalogSeq(),
                result.stale(),
                result.totalCredits(),
                result.maxCredits(),
                result.schedules().stream().map(CombinationsController::toDto).toList());
    }

    @ExceptionHandler(InvalidRequestException.class)
    public ResponseEntity<InvalidRequestDto> handleInvalidRequest(InvalidRequestException error) {
        return ResponseEntity.badRequest().body(new InvalidRequestDto("invalid_request", error.getMessage()));
    }

    /** Más específico que el manejador general: Spring elige este para el tope de créditos. */
    @ExceptionHandler(AppException.CreditLimitExceeded.class)
    public ResponseEntity<CreditLimitErrorDto> handleCreditLimit(AppException.CreditLimitExceeded error) {
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                .body(new CreditLimitErrorDto("credit_limit_exceeded", error.totalCredits(), error.maxCredits()));
    }

    @ExceptionHandler(AppException.class)
    public ResponseEntity<String> handle(AppException error) {
        HttpStatus status = switch (error) {
            case AppException.CatalogUnavailable e -> HttpStatus.SERVICE_UNAVAILABLE;
            case AppException.PortFailure e when e.kind() == PortException.Kind.UNAVAILABLE ->
                    HttpStatus.SERVICE_UNAVAILABLE;
            case AppException.PortFailure e -> HttpStatus.INTERNAL_SERVER_ERROR;
            case AppException.SequenceGap e -> HttpStatus.INTERNAL_SERVER_ERROR;
            case AppException.CreditLimitExceeded e -> HttpStatus.UNPROCESSABLE_ENTITY;
        };
        return ResponseEntity.status(status).body(error.getMessage());
    }

    private static List<TimeSlot> parseAvoid(List<SlotDto> avoid) {
        if (avoid == null) {
            return List.of();
        }
        List<TimeSlot> windows = new ArrayList<>();
        for (int i = 0; i < avoid.size(); i++) {
            SlotDto window = avoid.get(i);
            if (window == null || window.day() == null) {
                throw new InvalidRequestException("avoid[" + i + "]: falta el día");
            }
            Weekday day;
            try {
                day = Weekday.valueOf(window.day().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                throw new InvalidRequestException(
                        "avoid[" + i + "]: día inválido '" + window.day() + "' (monday ... sunday)");
            }
            windows.add(TimeSlot.of(day, window.startMinute(), window.endMinute())
                    .orElseThrow(() -> new InvalidRequestException(
                            "avoid[" + windows.size() + "]: se requiere 0 <= start_minute < end_minute <= 1440")));
        }
        return windows;
    }

    private static ScheduleDto toDto(ScoredSchedule scored) {
        Schedule schedule = scored.schedule();
        return new ScheduleDto(scored.penalty(), schedule.sections().stream()
                .map(s -> new SectionDto(
                        s.id().value(),
                        s.course().value(),
                        s.slots().stream()
                                .map(slot -> new SlotDto(slot.day().name().toLowerCase(), slot.start(), slot.end()))
                                .toList()))
                .toList());
    }
}
