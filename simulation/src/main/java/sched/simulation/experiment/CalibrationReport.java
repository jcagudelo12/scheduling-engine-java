package sched.simulation.experiment;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.ToIntFunction;

/**
 * Distribuciones simuladas del flujo A en las mismas categorías de A9, A11 y A13 de la consulta,
 * para compararlas con ella (la calibración de los parámetros vendrá después).
 *
 * <ul>
 *   <li><b>A9</b>, veces que rehizo la selección por cruces = {@code r_traslape} (cada rechazo por
 *       cruce obliga a rehacer la elección de esa asignatura).</li>
 *   <li><b>A11</b>, tiempo hasta confirmar = {@code t_total_s} en minutos; cae en la primera
 *       categoría cuyo límite superior lo cubre (&lt;15 excluyente; los demás incluyentes).</li>
 *   <li><b>A13</b>, perdió o casi perdió cupo mientras resolvía un cruce. La consulta no dice
 *       cuándo empieza y termina "resolver un cruce"; se opera como el periodo entre el primer cruce
 *       que encuentra el estudiante (rechazo de la plataforma o cruce que nota él) y la
 *       confirmación. <i>sí</i> = en ese periodo tuvo al menos un rechazo por cupo o encontró lleno
 *       un grupo que quería (se cruzaba) ({@code r_cupo_in_clash} &gt; 0); <i>casi</i> = no perdió,
 *       pero tomó un cupo en ese periodo cuando quedaban {@code nearLossSeats} cupos o menos
 *       ({@code near_loss} &gt; 0); <i>no</i> = ninguna de las dos (incluye a quien nunca tuvo un
 *       cruce). Una definición más estricta (solo en la asignatura con el cruce pendiente o en una
 *       retirada para resolverlo) se probó primero: no alcanzaba más de ~13 % de "sí" dentro de
 *       cotas plausibles; ver docs/simulador.md. Como variante se informa A13b, donde <i>sí</i> es
 *       cualquier rechazo por cupo ({@code r_cupo} &gt; 0) aunque sea antes de cualquier cruce, y
 *       <i>casi</i> mantiene su definición.</li>
 * </ul>
 */
public final class CalibrationReport {

    /** Una tabla: etiquetas, porcentaje de la consulta y porcentaje simulado. */
    public record Comparison(String name, List<String> labels, double[] survey, double[] simulated) {
    }

    private CalibrationReport() {
    }

    public static List<Comparison> compare(List<ResultRow> reactiveRows, Calibration calibration) {
        List<ResultRow> rows = reactiveRows.stream().filter(r -> r.condition().equals("A")).toList();
        List<Comparison> comparisons = new ArrayList<>();

        List<Calibration.Category> a9 = calibration.redoneSelection();
        comparisons.add(compare("A9: veces que rehizo la selección por cruces", a9, rows, r -> {
            for (int i = 0; i < a9.size(); i++) {
                Calibration.Category c = a9.get(i);
                if (r.rTraslape() >= c.min() && (c.max() == null || r.rTraslape() <= c.max())) {
                    return i;
                }
            }
            return a9.size() - 1;
        }));

        List<Calibration.Category> a11 = calibration.platformTime();
        comparisons.add(compare("A11: tiempo en la plataforma hasta confirmar", a11, rows, r -> {
            double minutes = r.tTotalSeconds() / 60.0;
            for (int i = 0; i < a11.size(); i++) {
                Calibration.Category c = a11.get(i);
                if (c.maxMinutes() == null || (c.maxInclusive() ? minutes <= c.maxMinutes() : minutes < c.maxMinutes())) {
                    return i;
                }
            }
            return a11.size() - 1;
        }));

        List<Calibration.Category> a13 = calibration.lostSeat();
        comparisons.add(compare("A13: perdió o casi perdió cupo al resolver un cruce", a13, rows,
                r -> r.rCupoInClash() > 0 ? index(a13, "si") : r.nearLoss() > 0 ? index(a13, "casi") : index(a13, "no")));
        comparisons.add(compare("A13b: variante, sí = cualquier rechazo por cupo", a13, rows,
                r -> r.rCupo() > 0 ? index(a13, "si") : r.nearLoss() > 0 ? index(a13, "casi") : index(a13, "no")));
        return comparisons;
    }

    private static int index(List<Calibration.Category> categories, String id) {
        for (int i = 0; i < categories.size(); i++) {
            if (id.equals(categories.get(i).id())) {
                return i;
            }
        }
        throw new IllegalStateException("la calibración no tiene la categoría " + id);
    }

    private static Comparison compare(
            String name, List<Calibration.Category> categories, List<ResultRow> rows, ToIntFunction<ResultRow> bin) {
        double[] counts = new double[categories.size()];
        rows.forEach(r -> counts[bin.applyAsInt(r)]++);
        double[] survey = new double[categories.size()];
        double surveyTotal = categories.stream().mapToDouble(Calibration.Category::percent).sum();
        for (int i = 0; i < survey.length; i++) {
            survey[i] = 100 * categories.get(i).percent() / surveyTotal;
            counts[i] = rows.isEmpty() ? 0 : 100 * counts[i] / rows.size();
        }
        return new Comparison(name, categories.stream().map(Calibration.Category::label).toList(), survey, counts);
    }

    public static String format(List<Comparison> comparisons, int simulatedProfiles) {
        StringBuilder out = new StringBuilder();
        out.append(String.format(Locale.ROOT, "Calibración del flujo A (n simulado = %d; consulta n = 135)%n", simulatedProfiles));
        for (Comparison c : comparisons) {
            out.append(String.format(Locale.ROOT, "%n%s%n  %-30s %10s %10s%n", c.name(), "categoría", "consulta %", "simulado %"));
            for (int i = 0; i < c.labels().size(); i++) {
                out.append(String.format(Locale.ROOT, "  %-30s %10.1f %10.1f%n", c.labels().get(i), c.survey()[i], c.simulated()[i]));
            }
        }
        return out.toString();
    }
}
