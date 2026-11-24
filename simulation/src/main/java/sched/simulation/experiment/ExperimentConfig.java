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
     * @param extraBlockProbability probabilidad de que una asignatura tenga un bloque semanal
     *     adicional (práctica o laboratorio) sobre los que le tocan por créditos
     * @param popularitySkew dispersión (sigma de una lognormal) de la popularidad de cada grupo
     *     (profesor, horario cómodo): los estudiantes pesan más los grupos populares al elegir,
     *     sin importar su cupo, y esos se agotan primero (0 = todos igual de populares)
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
            double seatUnevenness,
            double extraBlockProbability,
            double popularitySkew) {
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
     * @param rushShare fracción de los estudiantes de la ventana que entra en la avalancha de la
     *     apertura (todos a la hora exacta), con llegadas exponenciales desde el inicio de la ventana
     * @param rushMeanSeconds media de esas llegadas de la avalancha
     */
    public record ArrivalParams(
            List<Double> windowStartSeconds, List<Double> windowWeights, double arrivalsPerMinute,
            double rushShare, double rushMeanSeconds) {
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
     * @param usePreferences si el estudiante manda al motor sus ventanas a evitar (preferencia de
     *     jornada); {@code false} es una variación de sensibilidad: B sin preferencias
     */
    public record EngineParams(
            String solver, int maxResults, int maxAlternatives, String timeMode,
            double pickBestProbability, int pickTop, boolean usePreferences) {

        boolean measured() {
            return "measured".equals(timeMode);
        }
    }

    /**
     * Tiempos de las acciones del estudiante. Las acciones equivalentes en A y en B usan los
     * MISMOS parámetros: navegar la oferta de una asignatura, enviar (inscribir) un grupo, leer un
     * rechazo, retirar un grupo y confirmar. Solo son propios de una condición:
     * {@code chooseSection} (A: decidir el grupo entre los que ve) y {@code reviewCombination} y
     * {@code reviewAlternative} (B: revisar cada combinación o alternativa que entrega el motor).
     *
     * @param browseCourse navegar la página de la oferta de una asignatura (A: una vez por
     *     asignatura; B: una vez por grupo que inscribe, porque en la plataforma real hay que ir a
     *     la asignatura para inscribir el grupo)
     * @param chooseSection decidir qué grupo enviar (solo A)
     * @param submitSection enviar (inscribir) un grupo (A y B)
     * @param readRejection leer un rechazo o un mensaje (A y B)
     * @param withdraw retirar un grupo inscrito (A y B)
     * @param confirm confirmar la matrícula (A y B)
     * @param reviewCombination revisar UNA combinación de la lista del motor (solo B)
     * @param reviewAlternative revisar UNA alternativa del motor (solo B)
     */
    public record TimeParams(
            Dist browseCourse,
            Dist chooseSection,
            Dist submitSection,
            Dist readRejection,
            Dist withdraw,
            Dist confirm,
            Dist reviewCombination,
            Dist reviewAlternative) {
    }

    /**
     * Comportamiento del estudiante.
     *
     * @param preferenceStrength qué tanto pesa la jornada preferida al elegir grupo (0 = al azar)
     * @param clashAwareness media de la conciencia de cruces de quien hace ensayo y error: la
     *     probabilidad de notar a simple vista un cruce antes de enviar (A)
     * @param plannerShare fracción de estudiantes que armó el horario antes (en papel: A10 11,1 %);
     *     su conciencia de cruces es {@code plannerAwareness} (en media)
     * @param plannerAwareness media de la conciencia de cruces de quien planeó antes
     * @param awarenessConcentration concentración (a + b) de la beta de la conciencia de cruces;
     *     menor concentración = más heterogeneidad entre estudiantes
     * @param speedCv coeficiente de variación del factor de velocidad de cada estudiante (lognormal
     *     de media 1 que multiplica todos sus tiempos, igual en A y en B)
     * @param deferOnExhaustProbability probabilidad de aplazar la asignatura al agotar sus grupos
     *     por cruces, en lugar de retroceder (A14: 31,3 % la aplazó)
     * @param maxRetreatsPerCourse retrocesos (retirar un grupo inscrito) permitidos por asignatura (A)
     * @param maxSubmissions tope de envíos por estudiante en A; garantiza que termine
     * @param maxAlternativeRounds tope de alternativas adoptadas por estudiante en B
     * @param nearLossSeats un cupo tomado con este número de cupos o menos cuenta como "casi perdido"
     * @param staleViewProbability probabilidad de que, tras un rechazo, el estudiante NO recargue la
     *     página de la asignatura y elija con los cupos viejos (A): fuente de rechazos por cupo
     * @param combinationsReviewed combinaciones del motor que el estudiante revisa antes de elegir
     *     (tope; si hay menos, las que haya) (B)
     * @param alternativesReviewed alternativas del motor que revisa antes de adoptar la primera
     *     (tope) (B)
     * @param assistedBrowseFactor fracción del tiempo de navegar la asignatura que B paga por cada
     *     grupo que inscribe: 1 = lo mismo que A (supuesto base, conservador); 0 = el motor lleva
     *     directo al grupo (cota optimista, solo para sensibilidad)
     */
    public record BehaviorParams(
            double preferenceStrength,
            double clashAwareness,
            double plannerShare,
            double plannerAwareness,
            double awarenessConcentration,
            double speedCv,
            double deferOnExhaustProbability,
            int maxRetreatsPerCourse,
            int maxSubmissions,
            int maxAlternativeRounds,
            int nearLossSeats,
            double staleViewProbability,
            int combinationsReviewed,
            int alternativesReviewed,
            double assistedBrowseFactor) {
    }

    public static ExperimentConfig defaults() {
        return new ExperimentConfig(
                42, 500, "",
                new OfferParams(null, 1.1, 20, 5, 2, 4, 2, 4, 2, 6, 25, 2,
                        new ShiftWeights(0.40, 0.30, 0.30), 0.85, 0.7, 0.7, 0.2, 0.5),
                new PopulationParams(new ShiftWeights(0.40, 0.25, 0.35)),
                new ArrivalParams(List.of(0.0, 86_400.0, 172_800.0), List.of(1.0, 1.0, 1.0), 6.0, 0.3, 120),
                new PlatformParams(2.0),
                new EngineParams("forward-checking", 50, 5, "measured", 0.7, 5, true),
                new TimeParams(
                        new Dist(150, 0.6), new Dist(70, 0.6), new Dist(20, 0.5), new Dist(60, 0.6),
                        new Dist(45, 0.5), new Dist(90, 0.5), new Dist(25, 0.6), new Dist(25, 0.6)),
                new BehaviorParams(2.0, 0.15, 0.111, 0.9, 4.0, 0.5, 0.31, 2, 60, 10, 2, 0.3, 5, 3, 1.0));
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

    /** Valor numérico de un parámetro por su ruta con puntos (p. ej. {@code times.browseCourse.meanSeconds}). */
    public double valueAt(String path) {
        JsonNode node = mapper().valueToTree(this);
        for (String part : path.split("\\.")) {
            node = node.get(part);
            if (node == null) {
                throw new IllegalArgumentException("parámetro desconocido: " + path);
            }
        }
        return node.asDouble();
    }

    /**
     * Copia con los parámetros dados (ruta con puntos a valor). Los parámetros enteros se redondean.
     * Una ruta desconocida es un error.
     */
    public ExperimentConfig withValues(Map<String, Double> values) {
        JsonMapper mapper = mapper();
        JsonNode tree = mapper.valueToTree(this);
        for (Map.Entry<String, Double> entry : values.entrySet()) {
            String[] parts = entry.getKey().split("\\.");
            JsonNode parent = tree;
            for (int i = 0; i < parts.length - 1; i++) {
                parent = parent.get(parts[i]);
                if (parent == null) {
                    throw new IllegalArgumentException("parámetro desconocido: " + entry.getKey());
                }
            }
            String leaf = parts[parts.length - 1];
            JsonNode current = parent.get(leaf);
            if (!(parent instanceof ObjectNode object) || current == null || !current.isNumber()) {
                throw new IllegalArgumentException("parámetro numérico desconocido: " + entry.getKey());
            }
            if (current.isIntegralNumber()) {
                object.put(leaf, Math.round(entry.getValue()));
            } else {
                object.put(leaf, entry.getValue());
            }
        }
        return mapper.treeToValue(tree, ExperimentConfig.class).validated();
    }

    ExperimentConfig validated() {
        require(profiles >= 0, "profiles no puede ser negativo");
        require(arrivals.windowStartSeconds().size() == arrivals.windowWeights().size()
                && !arrivals.windowWeights().isEmpty(), "arrivals: windowStartSeconds y windowWeights deben tener el mismo tamaño");
        require(arrivals.rushShare() >= 0 && arrivals.rushShare() <= 1, "arrivals.rushShare debe estar en [0, 1]");
        require(arrivals.rushMeanSeconds() > 0, "arrivals.rushMeanSeconds debe ser positiva");
        require(arrivals.arrivalsPerMinute() > 0, "arrivals.arrivalsPerMinute debe ser positiva");
        require(platform.seatEventLatencySeconds() >= 0, "platform.seatEventLatencySeconds no puede ser negativa");
        require(offer.extraBlockProbability() >= 0 && offer.extraBlockProbability() <= 1,
                "offer.extraBlockProbability debe estar en [0, 1]");
        require(behavior.staleViewProbability() >= 0 && behavior.staleViewProbability() <= 1,
                "behavior.staleViewProbability debe estar en [0, 1]");
        require(offer.popularitySkew() >= 0, "offer.popularitySkew no puede ser negativa");
        require(offer.slackFactor() > 0, "offer.slackFactor debe ser positivo");
        require(offer.minSections() >= 1 && offer.maxSections() >= offer.minSections(), "offer: rango de grupos inválido");
        require(offer.creditsMin() >= 0 && offer.creditsMax() >= offer.creditsMin(), "offer: rango de créditos inválido");
        require(offer.coursesPerSemester() >= offer.sharedCoursesPerSemester(),
                "offer.sharedCoursesPerSemester no puede superar coursesPerSemester");
        require("forward-checking".equals(engine.solver()) || "backtracking".equals(engine.solver()),
                "engine.solver debe ser forward-checking o backtracking");
        require("measured".equals(engine.timeMode()) || "zero".equals(engine.timeMode()),
                "engine.timeMode debe ser measured o zero");
        require(behavior.plannerShare() >= 0 && behavior.plannerShare() <= 1, "behavior.plannerShare debe estar en [0, 1]");
        require(behavior.awarenessConcentration() > 0, "behavior.awarenessConcentration debe ser positiva");
        require(behavior.speedCv() >= 0, "behavior.speedCv no puede ser negativo");
        require(behavior.assistedBrowseFactor() >= 0, "behavior.assistedBrowseFactor no puede ser negativo");
        require(behavior.combinationsReviewed() >= 1 && behavior.alternativesReviewed() >= 1,
                "behavior: combinationsReviewed y alternativesReviewed deben ser al menos 1");
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
