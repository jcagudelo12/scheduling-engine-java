package sched.application;

import sched.domain.Schedule;

/**
 * Combinación con su penalización por las preferencias del estudiante (0 si no tiene o no se
 * incumple ninguna).
 */
public record ScoredSchedule(Schedule schedule, long penalty) {
}
