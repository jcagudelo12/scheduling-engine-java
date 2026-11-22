package sched.adapters.http;

import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import sched.adapters.http.CombinationsController.InvalidRequestDto;

/**
 * Errores de lectura del cuerpo, comunes a todos los endpoints: JSON mal formado, cuerpo
 * ausente o campos con el tipo equivocado son {@code 400} (no {@code 500}); un cuerpo que supera
 * el máximo es {@code 413}. Debe registrarse como bean (ver {@code EngineConfig}).
 */
@RestControllerAdvice
public class HttpExceptionHandler {

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<InvalidRequestDto> handleUnreadable(HttpMessageNotReadableException error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof BodyTooLargeException tooLarge) {
                return HttpErrors.payloadTooLarge(tooLarge.maxBytes());
            }
        }
        return HttpErrors.invalidRequest(
                "el cuerpo no es un JSON válido, falta, o algún campo tiene un tipo incorrecto "
                        + "(se espera un objeto con strings, listas y números según corresponda)");
    }
}
