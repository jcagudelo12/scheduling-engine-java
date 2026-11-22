package sched.domain;

/**
 * Reglas de matrícula del periodo, definidas por la institución y cargadas junto con la oferta.
 *
 * @param maxCredits tope de créditos que un estudiante puede matricular en el periodo. {@code 0}
 *     significa <b>sin tope</b> (también es el valor cuando la institución no envía reglas)
 */
public record EnrollmentRules(int maxCredits) {

    /** Sin ninguna restricción. */
    public static final EnrollmentRules NONE = new EnrollmentRules(0);

    public EnrollmentRules {
        if (maxCredits < 0) {
            throw new IllegalArgumentException("max_credits negativo: " + maxCredits);
        }
    }

    public boolean hasCreditLimit() {
        return maxCredits > 0;
    }

    /** {@code true} si {@code totalCredits} supera el tope (nunca, si no hay tope). */
    public boolean exceedsCreditLimit(int totalCredits) {
        return hasCreditLimit() && totalCredits > maxCredits;
    }
}
