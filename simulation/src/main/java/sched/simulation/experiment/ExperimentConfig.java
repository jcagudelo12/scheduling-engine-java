package sched.simulation.experiment;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;
import java.util.random.RandomGenerator;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Parámetros del experimento. Todos tienen un valor por defecto (los de {@link #defaults()});
 * el JSON de configuración solo necesita traer lo que cambia, y un nombre desconocido es un error
 * (un typo no debe pasar en silencio). Los valores por defecto son SUPUESTOS, no mediciones:
 * ver docs/simulador.md.
 *
 * @param seed semilla de la que salen todos los flujos de aleatoriedad
 * @param profiles cantidad de perfiles (estudiantes sintéticos)
 * @param calibrationFile JSON con las distribuciones de la consulta; vacío = el que viene en el jar
 */
public record ExperimentConfig(
        long seed,
        int profiles,
        String calibrationFile,
        OfferParams offer,
        PopulationParams population,
        ArrivalParams arrivals,
        PlatformParams platform,
        EngineParams engine,
        TimeParams times,
        BehaviorParams behavior) {

    /** Tiempo con distribución lognormal, dada por su media y su coeficiente de variación. */
    public record Dist(double meanSeconds, double cv) {

        public double sample(RandomGenerator random) {
            if (cv <= 0) {
                return meanSeconds;
            }
            double sigma2 = Math.log(1 + cv * cv);
            double mu = Math.log(meanSeconds) - sigma2 / 2;
            return Math.exp(mu + Math.sqrt(sigma2) * random.nextGaussian());
        }
    }

    /** Pesos relativos por jornada. */
    public record ShiftWeights(double manana, double tarde, double noche) {

        double[] asArray() {
            return new double[] {manana, tarde, noche};
        }

        Shift pick(RandomGenerator random) {
            return Shift.values()[Seeds.pick(random, asArray())];
        }

        /** Jornada de mayor peso. */
        Shift mostPopular() {
            double[] w = asArray();
            int best = 0;
            for (int i = 1; i < w.length; i++) {
                if (w[i] > w[best]) {
                    best = i;
                }
            }
            return Shift.values()[best];
        }
    }

    /**
     * Oferta sintética.
     *
     * @param file escenario JSON (formato de {@code Scenario}) con una oferta real; si se indica,
     *     se usa en lugar de generarla y el resto de parámetros de oferta se ignoran
     * @param slackFactor cupo total de una asignatura = demanda × este factor (holgura)
     * @param maxCredits tope de créditos del periodo
     * @param coursesPerSemester asignaturas por programa y semestre
     * @param sharedCoursesPerSemester de ellas, cuántas son comunes a todos los programas
     * @param sharedUpToSemester hasta qué semestre hay asignaturas comunes
     * @param targetGroupSize tamaño de grupo con el que se decide cuántos grupos tiene una asignatura
     * @param minSeatsPerSection cupo mínimo de un grupo
     * @param sectionShift probabilidad de la jornada de cada grupo
     * @param shiftAlignment probabilidad de que un bloque caiga en la jornada del grupo
     * @param popularSeatShare factor de cupo de los grupos de la jornada más pedida (menor que 1
     *     los deja escasos frente a su demanda)
     * @param seatUnevenness desigualdad del reparto de cupos entre grupos (0 = parejo)
     */
    public record OfferParams(
            String file,
            double slackFactor,
            int maxCredits,
            int coursesPerSemester,
            int sharedCoursesPerSemester,
            int sharedUpToSemester,
            int creditsMin,
            int creditsMax,
            int minSections,
            int maxSections,
            double targetGroupSize,
            int minSeatsPerSection,
            ShiftWeights sectionShift,
            double shiftAlignment,
            double popularSeatShare,
            double seatUnevenness) {
    }

    /** @param preference jornada preferida de los estudiantes (supuesto: la consulta no lo preguntó) */
    public record PopulationParams(ShiftWeights preference) {
    }

    /**
     * Llegadas por ventanas de matrícula: dentro de cada una, proceso de Poisson.
     *
     * @param windowStartSeconds inicio de cada ventana (reloj simulado)
     * @param windowWeights proporción de estudiantes de cada ventana
     * @param arrivalsPerMinute tasa de llegadas de Poisson dentro de una ventana
     */
    public record ArrivalParams(
            List<Double> windowStartSeconds, List<Double> windowWeights, double arrivalsPerMinute) {
    }

    /** @param seatEventLatencySeconds demora entre un cambio de cupo en la plataforma y la réplica del motor */
    public record PlatformParams(double seatEventLatencySeconds) {
    }

    /**
     * Motor.
     *
     * @param solver {@code forward-checking} o {@code backtracking}
     * @param maxResults combinaciones que pide el estudiante
     * @param maxAlternatives alternativas que pide el estudiante
     * @param timeMode {@code measured}: el cómputo real del motor (nanoTime) se suma al tiempo
     *     simulado; {@code zero}: no se suma (reproducibilidad exacta; para pruebas)
     * @param pickBestProbability probabilidad de que el estudiante elija la primera combinación
     * @param pickTop si no, elige uniforme entre las primeras {@code pickTop}
     */
    public record EngineParams(
            String solver, int maxResults, int maxAlternatives, String timeMode,
            double pickBestProbability, int pickTop) {

        boolean measured() {
            return "measured".equals(timeMode);
        }
    }

    /** Tiempos de las acciones del estudiante. */
    public record TimeParams(
            Dist browseCourse,
            Dist chooseAndSubmit,
            Dist readRejection,
            Dist withdraw,
            Dist submitChosen,
            Dist reviewCombinations,
            Dist reviewAlternatives,
            Dist confirm) {
    }

    /**
     * Comportamiento del estudiante.
     *
     * @param preferenceStrength qué tanto pesa la jornada preferida al elegir grupo (0 = al azar)
     * @param clashAwareness probabilidad de notar a simple vista un cruce antes de enviar (A)
     * @param deferOnExhaustProbability probabilidad de aplazar la asignatura al agotar sus grupos
     *     por cruces, en lugar de retroceder (A14: 31,3 % la aplazó)
     * @param maxRetreatsPerCourse retrocesos (retirar un grupo inscrito) permitidos por asignatura (A)
     * @param maxSubmissions tope de envíos por estudiante en A; garantiza que termine
     * @param maxAlternativeRounds tope de alternativas adoptadas por estudiante en B
     * @param nearLossSeats un cupo tomado con este número de cupos o menos cuenta como "casi perdido"
     */
    public record BehaviorParams(
            double preferenceStrength,
            double clashAwareness,
            double deferOnExhaustProbability,
            int maxRetreatsPerCourse,
            int maxSubmissions,
            int maxAlternativeRounds,
            int nearLossSeats) {
    }

    public static ExperimentConfig defaults() {
        return new ExperimentConfig(
                42, 500, "",
                new OfferParams(null, 1.1, 20, 5, 2, 4, 2, 4, 2, 6, 25, 2,
                        new ShiftWeights(0.40, 0.30, 0.30), 0.85, 0.7, 0.7),
                new PopulationParams(new ShiftWeights(0.40, 0.25, 0.35)),
                new ArrivalParams(List.of(0.0, 86_400.0, 172_800.0), List.of(1.0, 1.0, 1.0), 6.0),
                new PlatformParams(2.0),
                new EngineParams("forward-checking", 50, 5, "measured", 0.7, 5),
                new TimeParams(
                        new Dist(150, 0.6), new Dist(90, 0.6), new Dist(60, 0.6), new Dist(45, 0.5),
                        new Dist(20, 0.5), new Dist(120, 0.6), new Dist(75, 0.6), new Dist(90, 0.5)),
                new BehaviorParams(2.0, 0.15, 0.31, 2, 60, 10, 2));
    }

    /** Carga el JSON sobre los valores por defecto. */
    public static ExperimentConfig load(Path path) {
        JsonMapper mapper = mapper();
        JsonNode merged = merge(mapper.valueToTree(defaults()), mapper.readTree(path));
        return mapper.treeToValue(merged, ExperimentConfig.class).validated();
    }

    static JsonMapper mapper() {
        return JsonMapper.builder().enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build();
    }

    private static JsonNode merge(JsonNode base, JsonNode override) {
        if (base instanceof ObjectNode target && override.isObject()) {
            for (Map.Entry<String, JsonNode> field : override.properties()) {
                JsonNode current = target.get(field.getKey());
                target.set(field.getKey(),
                        current != null && current.isObject() ? merge(current, field.getValue()) : field.getValue());
            }
            return target;
        }
        return override;
    }

    public ExperimentConfig withSeed(long newSeed) {
        return new ExperimentConfig(newSeed, profiles, calibrationFile, offer, population, arrivals, platform,
                engine, times, behavior);
    }

    public ExperimentConfig withProfiles(int count) {
        return new ExperimentConfig(seed, count, calibrationFile, offer, population, arrivals, platform,
                engine, times, behavior);
    }

    public ExperimentConfig withEngine(EngineParams newEngine) {
        return new ExperimentConfig(seed, profiles, calibrationFile, offer, population, arrivals, platform,
                newEngine, times, behavior);
    }

    public ExperimentConfig withOffer(OfferParams newOffer) {
        return new ExperimentConfig(seed, profiles, calibrationFile, newOffer, population, arrivals, platform,
                engine, times, behavior);
    }

    public ExperimentConfig withTimes(TimeParams newTimes) {
        return new ExperimentConfig(seed, profiles, calibrationFile, offer, population, arrivals, platform,
                engine, newTimes, behavior);
    }

    public ExperimentConfig withPlatform(PlatformParams newPlatform) {
        return new ExperimentConfig(seed, profiles, calibrationFile, offer, population, arrivals, newPlatform,
                engine, times, behavior);
    }

    public ExperimentConfig withBehavior(BehaviorParams newBehavior) {
        return new ExperimentConfig(seed, profiles, calibrationFile, offer, population, arrivals, platform,
                engine, times, newBehavior);
    }

    ExperimentConfig validated() {
        require(profiles >= 0, "profiles no puede ser negativo");
        require(arrivals.windowStartSeconds().size() == arrivals.windowWeights().size()
                && !arrivals.windowWeights().isEmpty(), "arrivals: windowStartSeconds y windowWeights deben tener el mismo tamaño");
        require(arrivals.arrivalsPerMinute() > 0, "arrivals.arrivalsPerMinute debe ser positiva");
        require(platform.seatEventLatencySeconds() >= 0, "platform.seatEventLatencySeconds no puede ser negativa");
        require(offer.slackFactor() > 0, "offer.slackFactor debe ser positivo");
        require(offer.minSections() >= 1 && offer.maxSections() >= offer.minSections(), "offer: rango de grupos inválido");
        require(offer.creditsMin() >= 0 && offer.creditsMax() >= offer.creditsMin(), "offer: rango de créditos inválido");
        require(offer.coursesPerSemester() >= offer.sharedCoursesPerSemester(),
                "offer.sharedCoursesPerSemester no puede superar coursesPerSemester");
        require("forward-checking".equals(engine.solver()) || "backtracking".equals(engine.solver()),
                "engine.solver debe ser forward-checking o backtracking");
        require("measured".equals(engine.timeMode()) || "zero".equals(engine.timeMode()),
                "engine.timeMode debe ser measured o zero");
        require(engine.maxResults() > 0 && engine.maxAlternatives() > 0 && engine.pickTop() > 0,
                "engine: maxResults, maxAlternatives y pickTop deben ser positivos");
        return this;
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalArgumentException("configuración inválida: " + message);
        }
    }

    SplittableRandom rng(String stream, long index) {
        return Seeds.rng(seed, stream, index);
    }
}
