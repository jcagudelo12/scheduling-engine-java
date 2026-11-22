package sched.adapters.http;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import sched.adapters.http.CombinationsController.CreditLimitErrorDto;
import sched.adapters.http.CombinationsController.InvalidRequestDto;
import sched.application.AppException;
import sched.application.PortException;

/** Respuestas de error compartidas por los controladores de consulta. */
final class HttpErrors {

    private HttpErrors() {
    }

    static ResponseEntity<InvalidRequestDto> invalidRequest(String message) {
        return ResponseEntity.badRequest().body(new InvalidRequestDto("invalid_request", message));
    }

    /** 413: el cuerpo supera {@code SCHED_MAX_BODY_BYTES}. */
    static ResponseEntity<InvalidRequestDto> payloadTooLarge(long maxBytes) {
        return ResponseEntity.status(HttpStatus.CONTENT_TOO_LARGE)
                .body(new InvalidRequestDto("payload_too_large", payloadTooLargeMessage(maxBytes)));
    }

    static String payloadTooLargeMessage(long maxBytes) {
        return "el cuerpo de la solicitud supera el máximo de " + maxBytes + " bytes";
    }

    static ResponseEntity<CreditLimitErrorDto> creditLimit(AppException.CreditLimitExceeded error) {
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                .body(new CreditLimitErrorDto("credit_limit_exceeded", error.totalCredits(), error.maxCredits()));
    }

    static ResponseEntity<String> general(AppException error) {
        HttpStatus status = switch (error) {
            case AppException.CatalogUnavailable e -> HttpStatus.SERVICE_UNAVAILABLE;
            case AppException.PortFailure e when e.kind() == PortException.Kind.UNAVAILABLE ->
                    HttpStatus.SERVICE_UNAVAILABLE;
            case AppException.PortFailure e -> HttpStatus.INTERNAL_SERVER_ERROR;
            case AppException.SequenceGap e -> HttpStatus.INTERNAL_SERVER_ERROR;
            case AppException.CreditLimitExceeded e -> HttpStatus.UNPROCESSABLE_ENTITY;
            case AppException.InvalidSelection e -> HttpStatus.BAD_REQUEST;
        };
        return ResponseEntity.status(status).body(error.getMessage());
    }
}
