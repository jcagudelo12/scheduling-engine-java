package sched.application;

import sched.domain.EnrollmentRules;

/** Verificación del tope de créditos del periodo, común a los casos de uso que buscan horarios. */
final class CreditCheck {

    private CreditCheck() {
    }

    /** {@code true} si {@code totalCredits} supera el tope (nunca, si no hay tope). */
    static boolean exceeds(int totalCredits, int maxCredits) {
        return new EnrollmentRules(maxCredits).exceedsCreditLimit(totalCredits);
    }

    /** Lanza {@link AppException.CreditLimitExceeded} si los créditos superan el tope. */
    static void verify(int totalCredits, int maxCredits) throws AppException.CreditLimitExceeded {
        if (exceeds(totalCredits, maxCredits)) {
            throw new AppException.CreditLimitExceeded(totalCredits, maxCredits);
        }
    }
}
