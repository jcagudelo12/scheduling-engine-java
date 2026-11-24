package sched.simulation.analysis;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import sched.simulation.analysis.Analysis.MainHypothesis;
import sched.simulation.analysis.Analysis.PairedComparison;
import sched.simulation.analysis.Analysis.Result;
import sched.simulation.analysis.Analysis.SubgroupRow;
import sched.simulation.analysis.Descriptive.Summary;
import sched.simulation.analysis.Tests.Outcome;

/** Reporte en Markdown (tablas para el informe) del análisis. Punto decimal, 4 decimales. */
public final class AnalysisReport {

    private static final Map<String, String> VARIABLES = Map.of(
            "t_total_s", "Ttotal (s)", "r_traslape", "Rtraslape", "r_cupo", "Rcupo", "r_total", "Rtotal",
            "courses_enrolled", "Asignaturas matriculadas");
    private static final List<String> ORDER = List.of("t_total_s", "r_traslape", "r_cupo", "r_total",
            "courses_enrolled");

    private AnalysisReport() {
    }

    static String num(double v) {
        return Double.isNaN(v) ? "n/d" : String.format(Locale.ROOT, "%.4f", v);
    }

    static String pct(double v) {
        return String.format(Locale.ROOT, "%.1f %%", v * 100);
    }

    static String p(double v) {
        if (Double.isNaN(v)) {
            return "n/d";
        }
        return v < 0.0001 ? "< 0.0001" : String.format(Locale.ROOT, "%.4f", v);
    }

    /** "p < 0.0001" o "p = 0.0123", para frases. */
    static String pEq(double v) {
        return Double.isNaN(v) ? "p n/d" : v < 0.0001 ? "p < 0.0001" : String.format(Locale.ROOT, "p = %.4f", v);
    }

    public static String markdown(Result r, String input) {
        StringBuilder md = new StringBuilder();
        Analysis.Params prm = r.params();
        md.append("# Análisis estadístico del experimento\n\n");
        md.append("- Archivo de resultados: `").append(input).append("`\n");
        md.append("- Perfiles emparejados: ").append(r.profiles()).append('\n');
        md.append(String.format(Locale.ROOT, "- Nivel de significación alfa = %s; mu0 = %s; semilla = %d; remuestreos bootstrap = %d%n%n",
                prm.alpha(), prm.mu0(), prm.seed(), prm.bootstrapResamples()));

        md.append("## 1. Estadísticos descriptivos\n\n");
        md.append("| Condición | Variable | n | Media | Mediana | Desv. est. | RIC | Mín | Máx |\n");
        md.append("|---|---|---|---|---|---|---|---|---|\n");
        for (String cond : List.of("A", "B")) {
            for (String v : ORDER) {
                Summary s = r.descriptives().get(cond).get(v);
                md.append(String.format(Locale.ROOT, "| %s | %s | %d | %s | %s | %s | %s | %s | %s |%n", cond,
                        VARIABLES.get(v), s.n(), num(s.mean()), num(s.median()), num(s.sd()), num(s.iqr()),
                        num(s.min()), num(s.max())));
            }
        }
        md.append("\nA: reactivo; B: asistido. RIC: rango intercuartílico (cuantiles tipo 7).\n\n");

        main(md, r.main(), prm);
        rTotal(md, r.rTotal(), prm);

        md.append("## 4. Rtraslape en la condición B (condición de diseño)\n\n");
        if (r.clash().ok()) {
            md.append("CUMPLE: Rtraslape = 0 en los ").append(r.profiles()).append(" perfiles de la condición B.\n\n");
        } else {
            md.append("**FALLA** de la condición de diseño: ").append(r.clash().profilesWithClashInB())
                    .append(" perfiles de la condición B tienen Rtraslape distinto de 0 (el motor no debe permitir traslapes). ")
                    .append("Perfiles: ").append(String.join(", ", r.clash().profiles().stream().limit(20).toList()))
                    .append(r.clash().profiles().size() > 20 ? ", ..." : "").append("\n\n");
        }

        md.append("## 5. Amenaza a la validez: asignaturas matriculadas\n\n");
        Analysis.Validity v = r.validity();
        md.append("| Media A | Media B | Diferencia B - A | IC 95 % (t) |\n|---|---|---|---|\n");
        md.append(String.format(Locale.ROOT, "| %s | %s | %s | [%s, %s] |%n%n", num(v.meanA()), num(v.meanB()),
                num(v.difference()), num(v.ciLow()), num(v.ciHigh())));
        if (v.warning()) {
            md.append(String.format(Locale.ROOT,
                    "**AVISO**: |diferencia| = %s supera %.1f: una condición matricula en promedio más asignaturas que la otra, "
                            + "lo que amenaza la comparación de Ttotal (más materias cuestan más tiempo).%n%n",
                    num(Math.abs(v.difference())), v.threshold()));
        } else {
            md.append(String.format(Locale.ROOT, "Sin aviso: |diferencia| <= %.1f.%n%n", v.threshold()));
        }
        md.append("Perfiles con Ttotal_A = 0 excluidos de r: ").append(r.main().excluded());
        if (!r.main().excludedProfiles().isEmpty()) {
            md.append(" (").append(String.join(", ", r.main().excludedProfiles().stream().limit(20).toList()))
                    .append(')');
        }
        md.append("\n\n");

        md.append("## 6. Reducción relativa por subgrupos (descriptivo, sin pruebas)\n\n");
        md.append("| Variable | Valor | n | Reducción media | Reducción mediana |\n|---|---|---|---|---|\n");
        for (SubgroupRow s : r.subgroups()) {
            md.append(String.format(Locale.ROOT, "| %s | %s | %d | %s | %s |%n", s.variable(), s.value(), s.n(),
                    num(s.meanReduction()), num(s.medianReduction())));
        }
        power(md, r.power());
        return md.toString();
    }

    private static void power(StringBuilder md, Power.Check p) {
        md.append("\n## 7. Potencia y tamaño de muestra de la prueba principal\n\n");
        md.append(String.format(Locale.ROOT, "Con la desviación estándar observada de r (%s) y un efecto mínimo de interés de %s "
                        + "puntos sobre mu0 (aproximación normal unilateral, alfa = %s, corrección de Guenther): se necesitan "
                        + "**%d perfiles** para una potencia de 0,80 y **%d** para 0,90. Con los **%d perfiles usados** la potencia "
                        + "para ese efecto es %s.%n",
                num(p.sd()), num(p.delta() * 100), p.alpha(), p.requiredFor80(), p.requiredFor90(), p.nUsed(),
                num(p.powerAtN())));
    }

    private static void main(StringBuilder md, MainHypothesis m, Analysis.Params prm) {
        Outcome t = m.test();
        String mu = pct(prm.mu0());
        md.append("## 2. Hipótesis principal: reducción del tiempo total\n\n");
        md.append("r = (Ttotal_A - Ttotal_B) / Ttotal_A por perfil. H0: la reducción es <= ").append(mu)
                .append("; H1: la reducción es > ").append(mu).append(" (unilateral, alfa = ").append(prm.alpha())
                .append(").\n\n");
        md.append("| n | Media de r | Mediana de r | Desv. est. | RIC | Mín | Máx |\n|---|---|---|---|---|---|---|\n");
        Summary s = m.reduction();
        md.append(String.format(Locale.ROOT, "| %d | %s | %s | %s | %s | %s | %s |%n%n", s.n(), num(s.mean()),
                num(s.median()), num(s.sd()), num(s.iqr()), num(s.min()), num(s.max())));
        normality(md, m.normality());
        md.append("**Prueba elegida: ").append(t.test()).append(".** ").append(capital(m.reason())).append(".\n\n");
        md.append("| Prueba | Estadístico | gl | p (unilateral) | Decisión | ").append(t.effectName()).append(" |\n");
        md.append("|---|---|---|---|---|---|\n");
        md.append(String.format(Locale.ROOT, "| %s | %s | %s | %s | %s | %s |%n%n", t.test(), num(t.statistic()),
                num(t.df()), p(t.pValue()), m.reject() ? "se rechaza H0" : "no se rechaza H0", num(t.effectSize())));
        if (t.test().startsWith("Wilcoxon")) {
            md.append("Método del p: ").append(t.method()).append(". El estadístico es W+; el tamaño del efecto es r = Z/raíz(n) "
                    + "con el Z de la aproximación normal. La prueba compara la ubicación de r - mu0 con 0 "
                    + "(no es exactamente una prueba sobre la media).\n\n");
        }
        md.append("| Intervalo | Inferior | Superior |\n|---|---|---|\n");
        md.append(String.format(Locale.ROOT, "| IC 95 %% de la reducción media (t) | %s | %s |%n", num(m.meanCiLow()),
                num(m.meanCiHigh())));
        md.append(String.format(Locale.ROOT, "| IC 95 %% bootstrap percentil de la media (%d remuestreos, semilla %d) | %s | %s |%n%n",
                prm.bootstrapResamples(), prm.seed(), num(m.bootstrapCiLow()), num(m.bootstrapCiHigh())));
        md.append("**Conclusión.** ");
        if (m.reject()) {
            md.append("Se rechaza H0: la reducción media del tiempo total es mayor que el ").append(mu)
                    .append(String.format(Locale.ROOT, " (media = %s, mediana = %s, %s, %s = %s).",
                            pct(s.mean()), pct(s.median()), pEq(t.pValue()), t.effectName(), num(t.effectSize())));
        } else {
            md.append("No se rechaza H0: no hay evidencia suficiente de que la reducción media del tiempo total sea mayor que el ")
                    .append(mu)
                    .append(String.format(Locale.ROOT, " (media = %s, mediana = %s, %s, %s = %s).",
                            pct(s.mean()), pct(s.median()), pEq(t.pValue()), t.effectName(), num(t.effectSize())));
        }
        md.append(" Advertencia: el p-valor depende del número de perfiles simulados (con n grande cualquier diferencia "
                + "pequeña resulta significativa), por eso se acompaña del tamaño del efecto y de los intervalos de confianza.\n\n");
    }

    private static void rTotal(StringBuilder md, PairedComparison c, Analysis.Params prm) {
        Outcome two = c.twoSided();
        Outcome less = c.oneSidedBLessThanA();
        md.append("## 3. Rtotal: comparación pareada A frente a B\n\n");
        md.append("Diferencia d = Rtotal_B - Rtotal_A por perfil. Bilateral: H0: media de d = 0. Unilateral: H1: Rtotal_B < Rtotal_A.\n\n");
        Summary s = c.difference();
        md.append("| n | Media de d | Mediana | Desv. est. | RIC | Mín | Máx |\n|---|---|---|---|---|---|---|\n");
        md.append(String.format(Locale.ROOT, "| %d | %s | %s | %s | %s | %s | %s |%n%n", s.n(), num(s.mean()),
                num(s.median()), num(s.sd()), num(s.iqr()), num(s.min()), num(s.max())));
        normality(md, c.normality());
        md.append("**Prueba elegida: ").append(two.test()).append(".** ").append(capital(c.reason())).append(".\n\n");
        md.append("| Contraste | Estadístico | p | Decisión | ").append(two.effectName()).append(" |\n|---|---|---|---|---|\n");
        md.append(String.format(Locale.ROOT, "| Bilateral | %s | %s | %s | %s |%n", num(two.statistic()), p(two.pValue()),
                c.rejectTwoSided() ? "se rechaza H0" : "no se rechaza H0", num(two.effectSize())));
        md.append(String.format(Locale.ROOT, "| Unilateral (B < A) | %s | %s | %s | %s |%n%n", num(less.statistic()),
                p(less.pValue()), c.rejectOneSided() ? "se rechaza H0" : "no se rechaza H0", num(less.effectSize())));
        if (two.test().startsWith("Wilcoxon")) {
            md.append("Método del p: ").append(two.method()).append(".\n\n");
        }
        md.append(String.format(Locale.ROOT, "IC 95 %% (t) de la diferencia media: [%s, %s]. Las pruebas usan alfa = %s.%n%n",
                num(c.meanCiLow()), num(c.meanCiHigh()), prm.alpha()));
    }

    private static void normality(StringBuilder md, Analysis.Normality n) {
        md.append("Normalidad (Shapiro-Wilk) de ").append(n.sample()).append(": ");
        if (n.n() < ShapiroWilk.MIN_N || n.constant()) {
            md.append("no calculable").append(n.constant() ? " (muestra constante)" : "").append(".\n\n");
        } else {
            md.append(String.format(Locale.ROOT, "W = %s, %s%s.%n%n", num(n.w()), pEq(n.pValue()),
                    n.subsampled() ? " (submuestra aleatoria de 5000)" : ""));
        }
    }

    private static String capital(String s) {
        return s.substring(0, 1).toUpperCase(Locale.ROOT) + s.substring(1);
    }

    /** Resumen corto para la consola. */
    public static String console(Result r) {
        MainHypothesis m = r.main();
        Outcome t = m.test();
        StringBuilder out = new StringBuilder();
        out.append(String.format(Locale.ROOT, "análisis: perfiles=%d (excluidos de r: %d)%n", r.profiles(), m.excluded()));
        out.append(String.format(Locale.ROOT, "reducción relativa: media=%s mediana=%s IC95%% t=[%s, %s] bootstrap=[%s, %s]%n",
                num(m.reduction().mean()), num(m.reduction().median()), num(m.meanCiLow()), num(m.meanCiHigh()),
                num(m.bootstrapCiLow()), num(m.bootstrapCiHigh())));
        out.append(String.format(Locale.ROOT, "H1 (r > %s): %s, estadístico=%s %s %s=%s -> %s%n", pct(r.params().mu0()),
                t.test(), num(t.statistic()), pEq(t.pValue()), t.effectName(), num(t.effectSize()),
                m.reject() ? "se rechaza H0" : "no se rechaza H0"));
        PairedComparison c = r.rTotal();
        out.append(String.format(Locale.ROOT, "Rtotal (B - A): %s, media=%s p bilateral=%s p unilateral(B<A)=%s%n",
                c.twoSided().test(), num(c.difference().mean()), p(c.twoSided().pValue()),
                p(c.oneSidedBLessThanA().pValue())));
        out.append(r.clash().ok() ? "Rtraslape en B: 0 en todos los perfiles (cumple)\n"
                : "FALLA: Rtraslape en B distinto de 0 en " + r.clash().profilesWithClashInB() + " perfiles\n");
        if (r.validity().warning()) {
            out.append(String.format(Locale.ROOT, "AVISO: diferencia de asignaturas matriculadas B - A = %s (> %.1f)%n",
                    num(r.validity().difference()), r.validity().threshold()));
        }
        out.append(String.format(Locale.ROOT, "potencia: perfiles necesarios para efecto de %s puntos: %d (potencia 0,80), %d (0,90); "
                        + "con %d perfiles la potencia es %s%n", num(r.power().delta() * 100), r.power().requiredFor80(),
                r.power().requiredFor90(), r.power().nUsed(), num(r.power().powerAtN())));
        return out.toString();
    }
}
