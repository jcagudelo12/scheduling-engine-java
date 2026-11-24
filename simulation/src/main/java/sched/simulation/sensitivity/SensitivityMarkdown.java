package sched.simulation.sensitivity;

import java.util.List;
import java.util.Locale;

/** Reporte del análisis de sensibilidad. */
final class SensitivityMarkdown {

    private SensitivityMarkdown() {
    }

    private static String pct(double v) {
        return String.format(Locale.ROOT, "%.1f %%", v * 100);
    }

    private static String p(double v) {
        return v < 0.0001 ? "< 0.0001" : String.format(Locale.ROOT, "%.4f", v);
    }

    private static String f(double v, int d) {
        return String.format(Locale.ROOT, "%." + d + "f", v);
    }

    static String console(SensitivityCli.Outcome o) {
        StringBuilder out = new StringBuilder();
        for (SensitivityRunner.Row r : o.rows()) {
            out.append(String.format(Locale.ROOT, "%-18s r=%s IC95 boot=[%s, %s] p=%s -> %s%n", r.id(),
                    pct(r.meanReduction()), pct(r.bootstrapLow()), pct(r.bootstrapHigh()), p(r.pValue()),
                    r.reject() ? "se rechaza H0" : "NO se rechaza H0"));
        }
        SensitivityRunner.Replicas rep = o.replicas();
        out.append(String.format(Locale.ROOT, "réplicas (%d semillas): media de r=%s desv. est.=%s [%s, %s]; H0 rechazada en %d de %d%n",
                rep.count(), pct(rep.mean()), pct(rep.sd()), pct(rep.min()), pct(rep.max()), rep.rejections(), rep.count()));
        SensitivityRunner.Breakpoint b = o.breakpoint();
        SensitivityRunner.BrowseBreakpoint bb = o.browseBreakpoint();
        out.append(!bb.needed() ? "quiebre de navegación: no hace falta descuento\n"
                : String.format(Locale.ROOT, "quiebre de navegación: r cae al 20 %% si B paga %s %% de la navegación por grupo%n",
                        f(bb.factor() * 100, 0)));
        out.append(b.beyondMax()
                ? String.format(Locale.ROOT, "punto de quiebre: la reducción sigue sobre el 20 %% con los tiempos propios de B x%s%n", f(b.factor(), 0))
                : String.format(Locale.ROOT, "punto de quiebre: x%s sobre los tiempos propios de B (r = %s)%n", f(b.factor(), 2),
                        pct(b.reductionAtFactor())));
        return out.toString();
    }

    static String markdown(SensitivityCli.Outcome o, String configFile, double seconds) {
        StringBuilder md = new StringBuilder();
        List<SensitivityRunner.Row> rows = o.rows();
        md.append("# Análisis de sensibilidad de la conclusión principal\n\n");
        md.append("- Configuración base: `").append(configFile).append("`\n");
        md.append(String.format(Locale.ROOT, "- Semilla: %d; perfiles por corrida: %d; H0: r <= 20 %% (unilateral, alfa = 0,05); "
                        + "IC bootstrap percentil 95 %% con %d remuestreos; duración: %.0f s%n",
                o.config().seed(), o.config().profiles(), SensitivityRunner.BOOTSTRAP_RESAMPLES, seconds));
        md.append("- r = (Ttotal_A - Ttotal_B) / Ttotal_A por perfil. Cada fila es un experimento pareado completo (oferta, "
                + "perfiles, A y B) con la variación indicada y el análisis de `analyze` (Shapiro-Wilk, y t o Wilcoxon según el caso).\n\n");

        md.append("## 1. Variaciones\n\n");
        md.append("B no tiene datos empíricos: sus tiempos propios (revisar cada combinación y cada alternativa) son supuestos. "
                + "Las acciones que A y B comparten usan los mismos parámetros; B paga además navegar y enviar cada grupo.\n\n");
        md.append("| Variación | Qué cambia | Media de r | IC 95 % bootstrap | p | Decisión | Ttotal A (s) | Ttotal B (s) | Dif. matriculadas B - A |\n");
        md.append("|---|---|---|---|---|---|---|---|---|\n");
        long rejected = rows.stream().filter(SensitivityRunner.Row::reject).count();
        for (SensitivityRunner.Row r : rows) {
            md.append(String.format(Locale.ROOT, "| %s | %s | %s | [%s, %s] | %s | %s | %s | %s | %s |%n", r.id(), r.description(),
                    pct(r.meanReduction()), pct(r.bootstrapLow()), pct(r.bootstrapHigh()), p(r.pValue()),
                    r.reject() ? "se rechaza H0" : "**no se rechaza H0**", f(r.meanA(), 0), f(r.meanB(), 0),
                    f(r.enrolledGap(), 2)));
        }
        md.append(String.format(Locale.ROOT, "%nSe rechaza H0 en %d de %d variaciones.", rejected, rows.size()));
        List<String> kept = rows.stream().filter(r -> !r.reject()).map(SensitivityRunner.Row::id).toList();
        md.append(kept.isEmpty() ? " La conclusión resiste a todas las variaciones probadas.\n\n"
                : " **No se rechaza H0 en:** " + String.join(", ", kept) + ".\n\n");
        boolean clashFails = rows.stream().anyMatch(r -> !r.clashOk());
        md.append(clashFails ? "**FALLA** la condición de diseño (Rtraslape = 0 en B) en alguna variación.\n\n"
                : "La condición de diseño (Rtraslape = 0 en B) se cumple en todas las variaciones.\n\n");

        md.append("## 2. Punto de quiebre\n\n");
        SensitivityRunner.Breakpoint b = o.breakpoint();
        if (b.beyondMax()) {
            md.append(String.format(Locale.ROOT, "Incluso con los tiempos propios de B multiplicados por %s la reducción media sigue sobre el 20 %% "
                    + "(%s): no hay punto de quiebre dentro del rango buscado.%n%n", f(b.factor(), 0), pct(b.reductionAtFactor())));
        } else if (b.factor() <= 1) {
            md.append("Con la configuración base la reducción media ya es de ").append(pct(b.reductionAtBase()))
                    .append(", no mayor que el 20 %: no hay margen.\n\n");
        } else {
            md.append(String.format(Locale.ROOT, "Factor multiplicativo sobre los tiempos propios de B (revisar combinaciones y alternativas) con el que la "
                            + "reducción media cae al 20 %%: **x%s** (la reducción media es %s con la base y %s en el quiebre; bisección en escala "
                            + "logarítmica, %d corridas). Equivale a %s s por combinación revisada (base: %s s) y %s s por alternativa "
                            + "(base: %s s).%n%n",
                    f(b.factor(), 1), pct(b.reductionAtBase()), pct(b.reductionAtFactor()), b.runs(),
                    f(b.reviewCombinationSeconds(), 0), f(b.reviewCombinationSeconds() / b.factor(), 0),
                    f(b.reviewAlternativeSeconds(), 0), f(b.reviewAlternativeSeconds() / b.factor(), 0)));
        }

        SensitivityRunner.BrowseBreakpoint bb = o.browseBreakpoint();
        md.append("### Punto de quiebre de la navegación\n\n");
        md.append("Los tiempos propios de B no son lo que decide la conclusión: pesa más cuánto de la navegación de cada asignatura "
                + "debe pagar B al inscribir cada grupo que le sugiere el motor (en la base, lo mismo que A: ")
                .append(f(bb.browseSeconds(), 0)).append(" s por grupo).\n\n");
        if (!bb.needed()) {
            md.append("Con B pagando el 100 % de la navegación la reducción media es ").append(pct(bb.reductionAtFull()))
                    .append(", ya sobre el 20 %.\n\n");
        } else if (bb.factor() <= 0) {
            md.append("Ni siquiera sin pagar navegación en B la reducción media supera el 20 % (").append(pct(bb.reductionAtZero())).append(").\n\n");
        } else {
            md.append(String.format(Locale.ROOT, "La reducción media sube del %s (B paga el 100 %% de la navegación) al %s (B no paga navegación) y cruza el 20 %% "
                            + "cuando B paga **%s %%** de la navegación por grupo (unos %s s frente a %s s en A; bisección, %d corridas): para "
                            + "rechazar H0 el motor tendría que ahorrar al menos el %s %% de ese tiempo, por ejemplo llevando directo al grupo a "
                            + "inscribir.%n%n",
                    pct(bb.reductionAtFull()), pct(bb.reductionAtZero()), f(bb.factor() * 100, 0),
                    f(bb.factor() * bb.browseSeconds(), 0), f(bb.browseSeconds(), 0), bb.runs(), f((1 - bb.factor()) * 100, 0)));
        }

        md.append("## 3. Réplicas (variabilidad de la simulación)\n\n");
        SensitivityRunner.Replicas rep = o.replicas();
        md.append(String.format(Locale.ROOT, "La configuración base con %d semillas distintas (cada una genera su oferta, sus perfiles y sus llegadas): "
                        + "media de la reducción media = **%s**, desviación estándar entre réplicas = **%s**, mínimo %s, máximo %s; "
                        + "se rechaza H0 en %d de %d réplicas (p medio = %s).%n%n",
                rep.count(), pct(rep.mean()), pct(rep.sd()), pct(rep.min()), pct(rep.max()), rep.rejections(), rep.count(), p(rep.meanP())));
        md.append("| Semilla | Reducción media |\n|---|---|\n");
        for (int i = 0; i < rep.count(); i++) {
            md.append(String.format(Locale.ROOT, "| %d | %s |%n", rep.seeds().get(i), pct(rep.meanReductions().get(i))));
        }

        md.append("\n## 4. Tamaño de muestra\n\n");
        md.append(String.format(Locale.ROOT, "Con la desviación estándar observada de r en la base (%s) y un efecto mínimo de interés de %s puntos sobre el 20 %% "
                        + "(aproximación normal unilateral, alfa = 0,05, corrección de Guenther): **%d perfiles** para potencia 0,80 y **%d** para 0,90. "
                        + "Con los %d perfiles usados la potencia para ese efecto es %s. Se recomienda usar %d perfiles (el mayor entre 500 y el "
                        + "necesario para potencia 0,90, redondeado a la centena).%n%n",
                f(o.power().sd(), 4), f(o.power().delta() * 100, 0), o.power().requiredFor80(), o.power().requiredFor90(),
                o.power().nUsed(), f(o.power().powerAtN(), 4), o.recommendedProfiles()));

        md.append("## 5. Limitaciones\n\n");
        md.append("- Las variaciones se hacen de a una (salvo el caso pesimista combinado): no exploran interacciones.\n");
        md.append("- El quiebre se calcula solo sobre los tiempos propios de B; no cubre errores de especificación del comportamiento "
                + "(por ejemplo, que el estudiante de B no confíe en el motor y siga probando por su cuenta).\n");
        md.append("- El p-valor depende del número de perfiles; el tamaño del efecto y los intervalos son más informativos.\n");
        md.append("- El cómputo real del motor (`engine.timeMode = measured`) se suma al tiempo simulado; con varias corridas en "
                + "paralelo puede inflarse algo por contención de CPU (milisegundos frente a minutos).\n");
        return md.toString();
    }
}
