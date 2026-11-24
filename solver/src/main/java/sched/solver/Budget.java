package sched.solver;

/**
 * Reloj de una búsqueda. Consultar {@link System#nanoTime()} en cada nodo costaría más que el
 * propio nodo, así que se lee cada {@value #CHECK_EVERY} llamadas a {@link #expired()}. Una
 * instancia por búsqueda; no es segura entre hilos.
 */
final class Budget {

    /** Potencia de dos: la comprobación es una máscara. */
    static final int CHECK_EVERY = 256;

    private final boolean limited;
    private final long start;
    private final long limitNanos;
    private int ticks;
    private boolean expired;

    private Budget(boolean limited, long limitNanos) {
        this.limited = limited;
        this.limitNanos = limitNanos;
        this.start = limited ? System.nanoTime() : 0;
    }

    static Budget of(Problem problem) {
        return problem.timeLimit() == null
                ? new Budget(false, 0)
                : new Budget(true, problem.timeLimit().toNanos());
    }

    /** Si la búsqueda se cortó por el tiempo; no lee el reloj. */
    boolean cut() {
        return expired;
    }

    /** {@code true} si el tiempo máximo ya venció; una vez vencido, siempre lo es. */
    boolean expired() {
        if (expired || !limited) {
            return expired;
        }
        // La primera llamada también lee el reloj: con un límite de cero se corta de inmediato.
        if ((ticks++ & (CHECK_EVERY - 1)) == 0 && System.nanoTime() - start >= limitNanos) {
            expired = true;
        }
        return expired;
    }
}
