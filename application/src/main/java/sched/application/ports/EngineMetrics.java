package sched.application.ports;

import java.time.Duration;

/**
 * Puerto de medición: lo que el núcleo reporta sobre su propio trabajo, sin saber quién lo recoge.
 * La implementación con Micrometer vive en {@code server}; por defecto ({@link #NOOP}) no hace
 * nada, así que el núcleo no depende de ninguna librería de métricas.
 *
 * <p>Solo se reportan cantidades y estados, nunca datos del estudiante ni del catálogo (el motor
 * no conserva datos personales). Las implementaciones deben ser baratas y no lanzar excepciones.
 */
public interface EngineMetrics {

    /** No mide nada. */
    EngineMetrics NOOP = new EngineMetrics() {
    };

    /** Caso de uso de consulta que ejecutó la búsqueda. */
    enum Operation {
        COMBINATIONS, ALTERNATIVES
    }

    /** Qué pasó con un cambio incremental que envió la institución. */
    enum IngestOutcome {
        /** Era el siguiente esperado y se escribió en el historial. */
        APPLIED,
        /** Ya se había aceptado; no se escribe de nuevo. */
        DUPLICATE,
        /** Se perdieron eventos: se rechaza y las réplicas quedan desactualizadas hasta una carga completa. */
        GAP,
        /** No se pudo escribir en el historial; el {@code seq} puede reenviarse. */
        FAILED
    }

    /**
     * Una búsqueda terminó.
     *
     * @param elapsed duración de la búsqueda del solver (sin la lectura de la réplica)
     * @param results combinaciones encontradas
     * @param complete {@code false} si se cortó por el tiempo máximo (ADR 0009)
     * @param nodes nodos explorados, o un valor negativo si el solver no lo informa
     */
    default void searchFinished(Operation operation, Duration elapsed, int results, boolean complete, long nodes) {
    }

    /** Resultado de un cambio incremental de la institución. */
    default void ingestEvent(IngestOutcome outcome) {
    }

    /** Se escribió una carga completa en el historial. */
    default void snapshotIngested(int sections) {
    }

    /** La ingesta avisó que perdió la sincronía con la institución (hay que reenviar una carga completa). */
    default void syncLost() {
    }

    /** Mensajes del historial que el follower aún no ha leído; 0 cuando está al día. */
    default void followerLag(long pending) {
    }

    /** El follower perdió la conexión y empieza a releer el historial. */
    default void followerResync() {
    }
}
