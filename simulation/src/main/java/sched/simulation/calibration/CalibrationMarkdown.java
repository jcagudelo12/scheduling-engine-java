package sched.simulation.calibration;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import sched.simulation.experiment.CalibrationReport;

/** Reporte de la calibración (Markdown, consola y JSON de resumen). */
final class CalibrationMarkdown {

    /** Una tabla se considera reproducida si su distancia (media fuera de muestra) queda a menos de este múltiplo del piso de ruido... */
    static final double REPRODUCED_FACTOR = 2.0;
    /** ...parcialmente reproducida si queda a menos de este otro, y no reproducida si lo supera. */
    static final double PARTIAL_FACTOR = 4.0;

    private CalibrationMarkdown() {
    }

    private static String f(double v, int d) {
        return String.format(Locale.ROOT, "%." + d + "f", v);
    }

    private static String g(double v) {
        double a = Math.abs(v);
        return a >= 100 ? f(v, 0) : a >= 10 ? f(v, 1) : a >= 1 ? f(v, 2) : f(v, 3);
    }

    /** Media fuera de muestra de la distancia de una tabla, en múltiplos de su piso de ruido. */
    static double ratio(Calibrator.Outcome o, int table) {
        return o.validation().stream().mapToDouble(v -> v.distances()[table]).average().orElse(1) / o.floors()[table];
    }

    static boolean reproduced(Calibrator.Outcome o, int table) {
        return ratio(o, table) <= REPRODUCED_FACTOR;
    }

    static String verdict(Calibrator.Outcome o, int table) {
        double r = ratio(o, table);
        return r <= REPRODUCED_FACTOR ? "reproducida" : r <= PARTIAL_FACTOR ? "parcialmente reproducida"
                : "**NO reproducida**";
    }

    static String console(Calibrator.Outcome o) {
        StringBuilder out = new StringBuilder();
        out.append(String.format(Locale.ROOT, "distancia total: inicial=%s calibrada=%s fuera de muestra (semilla %d)=%s (media de %d semillas=%s)%n",
                f(o.initial().total(), 4), f(o.inSample().total(), 4), o.validationSeeds().getFirst(),
                f(o.outOfSample().total(), 4), o.validation().size(), f(o.validationMean(), 4)));
        for (int t = 0; t < 3; t++) {
            out.append(String.format(Locale.ROOT, "  %-4s inicial=%s calibrada=%s fuera=%s piso de ruido=%s%n",
                    Fit.TABLE_NAMES.get(t), f(o.initial().distances()[t], 4), f(o.inSample().distances()[t], 4),
                    f(o.outOfSample().distances()[t], 4), f(o.floors()[t], 4)));
        }
        return out.toString();
    }

    static String markdown(Calibrator.Outcome o, String configFile, int budget, double seconds) {
        StringBuilder md = new StringBuilder();
        md.append("# Calibración del flujo A con la consulta a estudiantes\n\n");
        md.append("- Configuración de partida: `").append(configFile).append("`\n");
        md.append(String.format(Locale.ROOT, "- Semilla de calibración: %d; perfiles por evaluación: %d; presupuesto: %d evaluaciones "
                        + "(%d usadas; %d de hipercubo latino y %d refinamientos Nelder-Mead); duración: %.0f s%n",
                o.base().seed(), o.base().profiles(), budget, o.search().evaluations(),
                o.search().latinHypercubePoints(), o.search().refinementStarts(), seconds));
        md.append("- Salida: `calibrated.json` (configuración completa, lista para `experiment`).\n\n");

        md.append("## 1. Método\n\n");
        md.append("Se buscan los parámetros del flujo A y los compartidos con B (tiempos de las acciones comunes, mezcla de "
                + "conciencia de cruces, heterogeneidad de velocidad, escasez de cupos y tasa de llegadas) que minimizan "
                + "la distancia entre las distribuciones simuladas por el flujo A y las de la consulta en A9 (veces que "
                + "rehizo la selección por cruces), A11 (tiempo en la plataforma) y A13 (perdió o casi perdió cupo). "
                + "La distancia por tabla es la de **Hellinger** (acotada en [0, 1], simétrica, definida con categorías "
                + "vacías y sensible a las categorías pequeñas); la total es la media ponderada con pesos iguales. "
                + "La búsqueda es un hipercubo latino (exploración global) seguido de Nelder-Mead desde los mejores "
                + "puntos (refinamiento local, en el cubo unitario normalizado y con las cotas como restricción), con "
                + "los mismos números aleatorios (la misma semilla) en todas las evaluaciones, de modo que las "
                + "diferencias entre dos puntos se deben a los parámetros y no al azar. Las evaluaciones corren en "
                + "paralelo. **Los tiempos propios de B no se calibran**: no hay datos de B; se varían en el análisis de "
                + "sensibilidad (`sensitivity`).\n\n");

        md.append("## 2. Distancia\n\n");
        md.append("| Tabla | Piso de ruido | Inicial | Calibrada (semilla ").append(o.base().seed())
                .append(") | Fuera de muestra (semilla ").append(o.validationSeeds().getFirst())
                .append(") | Media de ").append(o.validation().size()).append(" semillas nuevas | Veredicto |\n");
        md.append("|---|---|---|---|---|---|---|\n");
        for (int t = 0; t < 3; t++) {
            final int table = t;
            double mean = o.validation().stream().mapToDouble(v -> v.distances()[table]).average().orElse(Double.NaN);
            md.append(String.format(Locale.ROOT, "| %s | %s | %s | %s | %s | %s | %s |%n", Fit.TABLE_NAMES.get(t),
                    f(o.floors()[t], 4), f(o.initial().distances()[t], 4), f(o.inSample().distances()[t], 4),
                    f(o.outOfSample().distances()[t], 4), f(mean, 4),
                    verdict(o, t) + " (" + f(ratio(o, t), 1) + " x piso)"));
        }
        md.append(String.format(Locale.ROOT, "| **Total** | | %s | %s | %s | %s | |%n%n", f(o.initial().total(), 4),
                f(o.inSample().total(), 4), f(o.outOfSample().total(), 4), f(o.validationMean(), 4)));
        md.append("Distancia de Hellinger (0 = idénticas, 1 = sin categorías en común). El **piso de ruido** es la "
                + "distancia esperada entre la propia distribución de la consulta y una muestra de 135 estudiantes "
                + "sacada de ella (2.000 remuestreos): con n = 135 la consulta misma no se puede reproducir con "
                + "distancia menor sin sobreajustar. Veredicto por tabla, según la media fuera de muestra en múltiplos de su piso: "
                + "reproducida hasta ").append(f(REPRODUCED_FACTOR, 0)).append(" x, parcialmente reproducida hasta ")
                .append(f(PARTIAL_FACTOR, 0)).append(" x, no reproducida por encima.\n\n");

        md.append("## 3. Consulta frente a simulado\n\n");
        md.append("Porcentajes. *Calibrada*: la semilla de calibración; *fuera de muestra*: la semilla ")
                .append(o.validationSeeds().getFirst()).append(".\n\n");
        for (int t = 0; t < 3; t++) {
            CalibrationReport.Comparison in = o.inSample().tables().get(t);
            CalibrationReport.Comparison outc = o.outOfSample().tables().get(t);
            md.append("**").append(in.name()).append("**\n\n");
            md.append("| Categoría | Consulta | Simulado (calibrada) | Simulado (fuera de muestra) | Inicial |\n|---|---|---|---|---|\n");
            CalibrationReport.Comparison init = o.initial().tables().get(t);
            for (int i = 0; i < in.labels().size(); i++) {
                md.append(String.format(Locale.ROOT, "| %s | %s | %s | %s | %s |%n", in.labels().get(i),
                        f(in.survey()[i], 1), f(in.simulated()[i], 1), f(outc.simulated()[i], 1),
                        f(init.simulated()[i], 1)));
            }
            md.append('\n');
        }
        md.append(String.format(Locale.ROOT, "Otros indicadores del flujo A calibrado: media de rechazos por cruce = %s, por cupo = %s; "
                        + "asignaturas matriculadas / pedidas = %s (inicial: %s). Un valor muy bajo de este último sería una "
                        + "señal de que la escasez calibrada es poco realista.%n%n",
                f(o.inSample().meanClashes(), 2), f(o.inSample().meanSeatRejections(), 2),
                f(o.inSample().enrolledShare(), 3), f(o.initial().enrolledShare(), 3)));

        md.append("## 4. Parámetros calibrados y sus cotas\n\n");
        md.append("| Parámetro | Ruta(s) | Cotas | Inicial | Calibrado | En la cota | Sensibilidad local (ΔD a ±")
                .append(f(Calibrator.LOCAL_STEP * 100, 0)).append(" % del rango) | Identificable |\n");
        md.append("|---|---|---|---|---|---|---|---|\n");
        List<Calibrator.Sensitivity> sens = o.sensitivities();
        for (int i = 0; i < sens.size(); i++) {
            Calibrator.Sensitivity s = sens.get(i);
            ParamSpec p = s.spec();
            md.append(String.format(Locale.ROOT, "| %s | `%s` | [%s, %s] | %s | %s | %s | %s | %s |%n", p.name(),
                    p.paths().size() > 1 ? p.paths().getFirst() + " (y otros %d)".formatted(p.paths().size() - 1)
                            : p.paths().getFirst(),
                    g(p.lo()), g(p.hi()), g(o.initialValues()[i]), g(o.values()[i]),
                    s.atBound() ? "**sí**" : "no", f(s.maxDelta(), 4), s.identifiable() ? "sí" : "**no**"));
        }
        md.append("\nJustificación de las cotas:\n\n");
        for (ParamSpec p : o.space().specs()) {
            md.append("- ").append(p.name()).append(": ").append(p.rationale()).append(".\n");
        }
        md.append("\n**Parámetros no identificables.** Un parámetro es no identificable si mover su valor ")
                .append(f(Calibrator.LOCAL_STEP * 100, 0)).append(" % de su rango (a cada lado, con todo lo demás en el óptimo) cambia la "
                        + "distancia total menos de ").append(f(Calibrator.IDENTIFIABILITY_THRESHOLD, 3))
                .append(". Para esos parámetros, el valor reportado es uno entre muchos igual de buenos: no debe leerse como una "
                        + "estimación. Son: ");
        List<String> weak = new ArrayList<>();
        sens.stream().filter(s -> !s.identifiable()).forEach(s -> weak.add(s.spec().name()));
        md.append(weak.isEmpty() ? "ninguno." : String.join("; ", weak) + ".").append("\n\n");

        md.append("## 5. Hallazgos de validez\n\n");
        boolean any = false;
        for (int t = 0; t < 3; t++) {
            if (!reproduced(o, t)) {
                any = true;
                boolean partial = ratio(o, t) <= PARTIAL_FACTOR;
                md.append("- **").append(Fit.TABLE_NAMES.get(t)).append(partial ? " se reproduce solo parcialmente" : " NO se reproduce")
                        .append("** dentro de las cotas plausibles (distancia fuera de muestra = ").append(f(ratio(o, t), 1))
                        .append(" veces el piso de ruido). Es una limitación del modelo de comportamiento, no algo que se corrija "
                                + "ampliando las cotas hasta valores sin sentido; el flujo A calibrado no representa bien ese fenómeno y la "
                                + "conclusión sobre la reducción de Ttotal debe leerse con esa advertencia.\n");
            }
        }
        for (Calibrator.Sensitivity s : sens) {
            if (s.atBound()) {
                any = true;
                md.append("- `").append(s.spec().name()).append("` quedó en una cota (").append(g(s.value()))
                        .append("): el ajuste pide un valor en el borde o fuera de lo que se consideró plausible.\n");
            }
        }
        if (!any) {
            md.append("- Ninguna tabla se salió del piso de ruido y ningún parámetro quedó en una cota.\n");
        }
        md.append("\n## 6. Limitaciones\n\n");
        md.append("- Se calibra con una consulta de 135 estudiantes: las propias distribuciones objetivo tienen error de "
                + "muestreo (ver piso de ruido). La distancia fuera de muestra cambia la semilla del simulador, no la "
                + "consulta.\n");
        md.append("- Con ").append(o.space().size()).append(" parámetros y tres tablas (14 categorías, 11 grados de libertad) el "
                + "problema es sub-determinado: hay combinaciones distintas igual de buenas. Que A9, A11 y A13 se reproduzcan no prueba que cada parámetro sea correcto "
                + "(ver los no identificables).\n");
        md.append("- La consulta no define cuándo empieza y termina \"resolver un cruce\" (A13): se operó como el periodo desde el primer "
                + "cruce que encuentra el estudiante hasta que confirma (ver `docs/simulador.md`). Con otra definición, el ajuste de A13 cambia.\n");
        md.append("- B no tiene datos empíricos: sus tiempos propios son supuestos y se someten a sensibilidad.\n");
        return md.toString();
    }

    static Map<String, Object> summary(Calibrator.Outcome o, int budget) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("seed", o.base().seed());
        m.put("profiles", o.base().profiles());
        m.put("budget", budget);
        m.put("evaluations", o.search().evaluations());
        Map<String, Object> distance = new LinkedHashMap<>();
        distance.put("tables", Fit.TABLE_NAMES);
        distance.put("floor", o.floors());
        distance.put("initial", o.initial().distances());
        distance.put("calibrated", o.inSample().distances());
        distance.put("outOfSample", o.outOfSample().distances());
        distance.put("totalInitial", o.initial().total());
        distance.put("totalCalibrated", o.inSample().total());
        distance.put("totalOutOfSample", o.outOfSample().total());
        distance.put("totalValidationMean", o.validationMean());
        distance.put("validationSeeds", o.validationSeeds());
        m.put("distance", distance);
        List<Map<String, Object>> params = new ArrayList<>();
        for (int i = 0; i < o.sensitivities().size(); i++) {
            Calibrator.Sensitivity s = o.sensitivities().get(i);
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("name", s.spec().name());
            p.put("paths", s.spec().paths());
            p.put("lo", s.spec().lo());
            p.put("hi", s.spec().hi());
            p.put("initial", o.initialValues()[i]);
            p.put("calibrated", o.values()[i]);
            p.put("atBound", s.atBound());
            p.put("maxDelta", s.maxDelta());
            p.put("identifiable", s.identifiable());
            params.add(p);
        }
        m.put("parameters", params);
        return m;
    }
}
