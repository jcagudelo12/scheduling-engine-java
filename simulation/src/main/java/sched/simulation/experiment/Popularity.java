package sched.simulation.experiment;

import java.util.SplittableRandom;
import sched.domain.SectionId;

/**
 * Popularidad de un grupo (profesor, horario cómodo): un factor lognormal de media 1 que sale solo de
 * {@code (semilla, id del grupo)}, así que es el mismo en las dos condiciones y en todos los
 * estudiantes. Los estudiantes pesan más los grupos populares al elegir (A: al elegir un grupo; B:
 * al elegir entre las primeras combinaciones del motor), sin que el cupo lo refleje: esos grupos se
 * agotan primero. Con {@code popularitySkew} = 0 todos valen 1.
 */
final class Popularity {

    private Popularity() {
    }

    static double of(ExperimentConfig config, SectionId section) {
        double sigma = config.offer().popularitySkew();
        if (sigma <= 0) {
            return 1;
        }
        SplittableRandom random = Seeds.rng(config.seed(), "popularity", section.value().hashCode());
        return Math.exp(sigma * random.nextGaussian() - sigma * sigma / 2);
    }
}
