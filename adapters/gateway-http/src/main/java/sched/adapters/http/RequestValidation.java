package sched.adapters.http;

import java.util.List;
import java.util.Map;
import sched.adapters.http.CombinationsController.InvalidRequestException;

/** Validación de los campos de entrada: presencia, tamaño y forma de los ids. Lanza {@link InvalidRequestException}. */
final class RequestValidation {

    private RequestValidation() {
    }

    /** Un id obligatorio: no nulo, no vacío, de hasta {@code maxIdLength} caracteres de {@code [A-Za-z0-9_.-]}. */
    static String id(String field, String value, RequestLimits limits) {
        if (value == null) {
            throw new InvalidRequestException("falta " + field);
        }
        if (value.isBlank()) {
            throw new InvalidRequestException(field + " no puede estar vacío");
        }
        if (value.length() > limits.maxIdLength()) {
            throw new InvalidRequestException(
                    field + ": supera el máximo de " + limits.maxIdLength() + " caracteres");
        }
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            boolean allowed = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
                    || c == '-' || c == '_' || c == '.';
            if (!allowed) {
                throw new InvalidRequestException(
                        field + ": solo se permiten letras, dígitos, '-', '_' y '.' (sin espacios ni otros símbolos)");
            }
        }
        return value;
    }

    /** Una lista obligatoria de ids con a lo sumo {@code max} elementos. */
    static List<String> idList(String field, List<String> values, int max, RequestLimits limits) {
        if (values == null) {
            throw new InvalidRequestException("falta " + field);
        }
        checkSize(field, values.size(), max);
        for (int i = 0; i < values.size(); i++) {
            id(field + "[" + i + "]", values.get(i), limits);
        }
        return values;
    }

    /** Una lista opcional de ids ({@code null} = vacía) con a lo sumo {@code max} elementos. */
    static List<String> optionalIdList(String field, List<String> values, int max, RequestLimits limits) {
        return values == null ? List.of() : idList(field, values, max, limits);
    }

    /** Un objeto opcional {@code curso -> grupo} ({@code null} = vacío) con a lo sumo {@code max} elementos. */
    static Map<String, String> optionalIdMap(String field, Map<String, String> values, int max, RequestLimits limits) {
        if (values == null) {
            return Map.of();
        }
        checkSize(field, values.size(), max);
        values.forEach((course, section) -> {
            id(field + " (curso)", course, limits);
            id(field + "[" + course + "]", section, limits);
        });
        return values;
    }

    static void checkSize(String field, int size, int max) {
        if (size > max) {
            throw new InvalidRequestException(field + ": " + size + " elementos, el máximo es " + max);
        }
    }
}
