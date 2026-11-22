package sched.application;

import sched.domain.SequenceGapException;

/** Errores de los casos de uso. */
public abstract sealed class AppException extends Exception {

    private AppException(String message, Throwable cause) {
        super(message, cause);
    }

    /** El catálogo aún no se ha cargado. */
    public static final class CatalogUnavailable extends AppException {
        public CatalogUnavailable() {
            super("el catálogo aún no se ha cargado", null);
        }
    }

    /** Réplica desincronizada; hace falta una carga completa. */
    public static final class SequenceGap extends AppException {
        private final SequenceGapException gap;

        public SequenceGap(SequenceGapException gap) {
            super("réplica desincronizada (" + gap.getMessage() + "); hace falta una carga completa", gap);
            this.gap = gap;
        }

        public long expected() {
            return gap.expected();
        }

        public long received() {
            return gap.received();
        }
    }

    /** Los créditos de los cursos pedidos superan el tope del periodo; no se hizo la búsqueda. */
    public static final class CreditLimitExceeded extends AppException {
        private final int totalCredits;
        private final int maxCredits;

        public CreditLimitExceeded(int totalCredits, int maxCredits) {
            super("los cursos pedidos suman " + totalCredits + " créditos y el máximo es " + maxCredits, null);
            this.totalCredits = totalCredits;
            this.maxCredits = maxCredits;
        }

        public int totalCredits() {
            return totalCredits;
        }

        public int maxCredits() {
            return maxCredits;
        }
    }

    /** La selección actual del estudiante no es coherente con sus cursos o con el catálogo. */
    public static final class InvalidSelection extends AppException {
        public InvalidSelection(String message) {
            super(message, null);
        }
    }

    /** Falló un adaptador. */
    public static final class PortFailure extends AppException {
        private final PortException.Kind kind;

        public PortFailure(PortException cause) {
            super(cause.getMessage(), cause);
            this.kind = cause.kind();
        }

        public PortException.Kind kind() {
            return kind;
        }
    }
}
