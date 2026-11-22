package sched.adapters.http.auth;

/**
 * El token no es válido. El mensaje es solo un código genérico del motivo ({@link Reason#code()}):
 * nunca contiene el token ni su contenido, así que se puede registrar sin riesgo. El {@code kid}
 * es el de la cabecera, si se alcanzó a leer (lo controla quien envía el token: sanitícelo al registrarlo).
 */
public final class InvalidTokenException extends Exception {

    /** Motivos del rechazo. El cliente nunca los ve: siempre recibe el mismo 401. */
    public enum Reason {
        TOO_LARGE("token_demasiado_grande"),
        MALFORMED("token_mal_formado"),
        UNSUPPORTED_ALGORITHM("algoritmo_no_permitido"),
        UNSUPPORTED_HEADER("cabecera_no_permitida"),
        UNKNOWN_KID("kid_desconocido"),
        BAD_SIGNATURE("firma_invalida"),
        MISSING_CLAIM("claim_ausente_o_invalido"),
        EXPIRED("vencido"),
        NOT_YET_VALID("aun_no_valido"),
        LIFETIME("vida_invalida_o_excesiva"),
        ISSUER("emisor_incorrecto"),
        AUDIENCE("audiencia_incorrecta");

        private final String code;

        Reason(String code) {
            this.code = code;
        }

        public String code() {
            return code;
        }
    }

    private final Reason reason;
    private final String kid;

    InvalidTokenException(Reason reason, String kid) {
        super(reason.code(), null, false, false);
        this.reason = reason;
        this.kid = kid;
    }

    public Reason reason() {
        return reason;
    }

    /** El {@code kid} de la cabecera, o {@code null} si no se pudo leer. */
    public String kid() {
        return kid;
    }
}
