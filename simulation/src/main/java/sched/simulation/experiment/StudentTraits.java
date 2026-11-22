package sched.simulation.experiment;

import java.util.SplittableRandom;
import java.util.random.RandomGenerator;

/**
 * Rasgos propios de cada estudiante (heterogeneidad), sorteados una vez por perfil de un flujo
 * propio ({@code behavior-trait}), así que son idénticos en A y en B.
 *
 * <ul>
 *   <li><b>Conciencia de cruces</b>: probabilidad de notar a simple vista un cruce antes de enviar
 *       (solo A la usa). Mezcla de dos grupos con distribución beta: quien armó el horario antes
 *       (A10: 11,1 % en papel; media {@code plannerAwareness}) y quien hace ensayo y error (media
 *       {@code clashAwareness}). La mezcla produce la cola pesada de A9: unos pocos estudiantes
 *       con conciencia casi nula rehacen la selección muchas veces.</li>
 *   <li><b>Velocidad</b>: factor lognormal de media 1 que multiplica todos los tiempos humanos del
 *       estudiante (cola pesada de A11). Es el mismo en A y en B.</li>
 * </ul>
 */
record StudentTraits(double awareness, double speed) {

    static StudentTraits draw(ExperimentConfig config, int profileIndex) {
        ExperimentConfig.BehaviorParams b = config.behavior();
        SplittableRandom random = Seeds.rng(config.seed(), "behavior-trait", profileIndex);
        boolean planner = random.nextDouble() < b.plannerShare();
        double mean = planner ? b.plannerAwareness() : b.clashAwareness();
        double awareness = Seeds.beta(random, mean, b.awarenessConcentration());
        double speed = new ExperimentConfig.Dist(1, b.speedCv()).sample(random);
        return new StudentTraits(awareness, speed);
    }

    /** Un tiempo de la acción, escalado por la velocidad del estudiante. */
    double time(ExperimentConfig.Dist dist, RandomGenerator random) {
        return dist.sample(random) * speed;
    }
}
