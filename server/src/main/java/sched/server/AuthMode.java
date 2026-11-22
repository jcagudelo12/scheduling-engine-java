package sched.server;

/** Cómo se autentica a quien consulta por HTTP (ADR 0004). */
enum AuthMode {
    /** Token firmado por la institución en {@code Authorization: Bearer}. Es lo normal. */
    JWT,
    /** Sin autenticación: el contexto del estudiante llega en el cuerpo. Solo para desarrollo y pruebas. */
    NONE;

    static AuthMode parse(String value) {
        return switch (value) {
            case "jwt" -> JWT;
            case "none" -> NONE;
            default -> throw new IllegalArgumentException(
                    "SCHED_AUTH inválido: '" + value + "' (use jwt o none)");
        };
    }
}
