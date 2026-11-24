package sched.simulation.experiment;

/** Contadores de un estudiante durante una corrida. */
final class Tally {
    int rTraslape;
    int rCupo;
    int withdrawals;
    int rCupoInClash;
    int nearLoss;
    int engineCalls;
    long engineNanos;
    double finishedAt = Double.NaN;
}
