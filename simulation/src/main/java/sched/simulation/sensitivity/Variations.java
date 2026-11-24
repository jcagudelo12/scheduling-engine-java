package sched.simulation.sensitivity;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import sched.simulation.experiment.ExperimentConfig;

/** Catálogo de variaciones del análisis de sensibilidad sobre la conclusión principal. */
public final class Variations {

    /** Tiempos humanos de acciones compartidas por A y B (y el de elegir grupo, propio de A). */
    static final List<String> SHARED_TIMES = List.of(
            "times.browseCourse.meanSeconds", "times.chooseSection.meanSeconds", "times.submitSection.meanSeconds",
            "times.readRejection.meanSeconds", "times.withdraw.meanSeconds", "times.confirm.meanSeconds");
    /** Tiempos propios de B: revisar cada combinación y cada alternativa del motor. */
    static final List<String> B_OWN_TIMES = List.of(
            "times.reviewCombination.meanSeconds", "times.reviewAlternative.meanSeconds");

    private Variations() {
    }

    /** Multiplica por {@code factor} los parámetros dados. */
    public static ExperimentConfig scale(ExperimentConfig config, double factor, List<String> paths) {
        Map<String, Double> values = new LinkedHashMap<>();
        for (String path : paths) {
            values.put(path, config.valueAt(path) * factor);
        }
        return config.withValues(values);
    }

    public static ExperimentConfig set(ExperimentConfig config, String path, double value) {
        return config.withValues(Map.of(path, value));
    }

    private static String x(double f) {
        return String.format(Locale.ROOT, "x%s", f == Math.rint(f) ? String.valueOf((int) f) : String.valueOf(f));
    }

    /** Las variaciones mínimas pedidas, más la referencia (sin cambios) y un caso pesimista combinado. */
    public static List<Variation> standard() {
        List<Variation> v = new ArrayList<>();
        v.add(new Variation("base", "referencia", "configuración calibrada sin cambios", c -> c));
        for (double f : new double[] {0.75, 1.25}) {
            v.add(new Variation("shared-" + x(f), "tiempos compartidos",
                    "tiempos de navegar, elegir, enviar, leer un rechazo, retirar y confirmar " + x(f),
                    c -> scale(c, f, SHARED_TIMES)));
        }
        for (double f : new double[] {2, 3, 5}) {
            v.add(new Variation("b-own-" + x(f), "tiempos propios de B",
                    "tiempos de revisar cada combinación y cada alternativa " + x(f),
                    c -> scale(c, f, B_OWN_TIMES)));
        }
        v.add(new Variation("b-review-double", "tiempos propios de B",
                "B revisa el doble de combinaciones y de alternativas antes de elegir", c -> c.withValues(Map.of(
                        "behavior.combinationsReviewed", c.valueAt("behavior.combinationsReviewed") * 2,
                        "behavior.alternativesReviewed", c.valueAt("behavior.alternativesReviewed") * 2))));
        for (double f : new double[] {0.5, 0}) {
            v.add(new Variation("b-browse-" + x(f), "supuestos de B (cota optimista)",
                    "B paga " + (f == 0 ? "nada" : "la mitad") + " del tiempo de navegar la asignatura por grupo que inscribe (el motor lleva al grupo)",
                    c -> set(c, "behavior.assistedBrowseFactor", f)));
        }
        v.add(new Variation("slack-less", "escasez de cupos", "menos holgura: factor de holgura - 0,10",
                c -> set(c, "offer.slackFactor", Math.max(0.5, c.valueAt("offer.slackFactor") - 0.10))));
        v.add(new Variation("slack-more", "escasez de cupos", "más holgura: factor de holgura + 0,20",
                c -> set(c, "offer.slackFactor", c.valueAt("offer.slackFactor") + 0.20)));
        for (double s : new double[] {0, 2, 10, 30}) {
            v.add(new Variation("latency-" + (int) s, "latencia del evento de cupo",
                    "latencia entre el cambio de cupo y la réplica del motor: " + (int) s + " s",
                    c -> set(c, "platform.seatEventLatencySeconds", s)));
        }
        for (double f : new double[] {0.5, 2}) {
            v.add(new Variation("arrivals-" + x(f), "tasa de llegadas", "tasa de llegadas por minuto " + x(f),
                    c -> scale(c, f, List.of("arrivals.arrivalsPerMinute"))));
        }
        v.add(new Variation("b-no-preferences", "preferencias en B", "B sin preferencias de jornada (sin ventanas a evitar)",
                c -> c.withEngine(new ExperimentConfig.EngineParams(c.engine().solver(), c.engine().maxResults(),
                        c.engine().maxAlternatives(), c.engine().timeMode(), c.engine().pickBestProbability(),
                        c.engine().pickTop(), false))));
        v.add(new Variation("pessimistic", "caso pesimista combinado",
                "tiempos compartidos x0,75, tiempos propios de B x3, latencia 30 s, B sin preferencias",
                c -> {
                    ExperimentConfig p = scale(scale(c, 0.75, SHARED_TIMES), 3, B_OWN_TIMES);
                    p = set(p, "platform.seatEventLatencySeconds", 30);
                    return p.withEngine(new ExperimentConfig.EngineParams(p.engine().solver(), p.engine().maxResults(),
                            p.engine().maxAlternatives(), p.engine().timeMode(), p.engine().pickBestProbability(),
                            p.engine().pickTop(), false));
                }));
        return v;
    }
}
