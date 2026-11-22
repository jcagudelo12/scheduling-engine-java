package sched.solver;

import sched.domain.Schedule;

/** Una combinación con su costo total (suma del costo de sus grupos). */
public record Scored(Schedule schedule, long cost) {
}
