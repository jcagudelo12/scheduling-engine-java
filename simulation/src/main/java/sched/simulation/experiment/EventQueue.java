package sched.simulation.experiment;

import java.util.PriorityQueue;

/**
 * Cola de eventos discretos con reloj simulado (en segundos). Los empates se resuelven por
 * orden de inserción, así que la ejecución es determinista.
 */
final class EventQueue {

    private record Event(double time, long order, Runnable action) {
    }

    private final PriorityQueue<Event> events = new PriorityQueue<>((a, b) -> {
        int byTime = Double.compare(a.time, b.time);
        return byTime != 0 ? byTime : Long.compare(a.order, b.order);
    });
    private double now;
    private long counter;

    double now() {
        return now;
    }

    void at(double time, Runnable action) {
        if (time < now) {
            throw new IllegalArgumentException("evento en el pasado: " + time + " < " + now);
        }
        events.add(new Event(time, counter++, action));
    }

    void after(double delay, Runnable action) {
        if (delay < 0) {
            throw new IllegalArgumentException("retardo negativo: " + delay);
        }
        at(now + delay, action);
    }

    /** Ejecuta hasta vaciar la cola. */
    void run() {
        Event event;
        while ((event = events.poll()) != null) {
            now = event.time;
            event.action.run();
        }
    }
}
