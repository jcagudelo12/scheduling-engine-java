package sched.simulation.experiment;

/** Condición experimental: A es la línea base y B es la matrícula asistida por el motor. */
public enum Condition {
    /** Reactiva, como la plataforma actual: ensayo y error contra los rechazos. */
    REACTIVE("A"),
    /** Asistida: el estudiante pide combinaciones y alternativas al motor. */
    ASSISTED("B");

    private final String code;

    Condition(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }
}
