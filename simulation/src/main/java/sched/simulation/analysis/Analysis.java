package sched.simulation.analysis;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.ToDoubleFunction;
import sched.simulation.analysis.Descriptive.Summary;
import sched.simulation.analysis.PairedResults.Observation;
import sched.simulation.analysis.PairedResults.Pair;
import sched.simulation.analysis.Tests.Alternative;
import sched.simulation.analysis.Tests.Outcome;

/** Análisis estadístico del experimento pareado (sección 3.3.4 del documento). */
public final class Analysis {

    /** Diferencia máxima tolerada en asignaturas matriculadas entre B y A antes de avisar. */
    public static final double ENROLLED_GAP_WARNING = 0.1;
    static final double CONFIDENCE = 0.95;

    public record Params(double alpha, double mu0, long seed, int bootstrapResamples) {
        public static Params defaults() {
            return new Params(0.05, 0.20, 42, Bootstrap.DEFAULT_RESAMPLES);
        }
    }

    public record Normality(String sample, int n, double w, double pValue, boolean constant, boolean subsampled,
                            boolean normal) {
    }

    public record MainHypothesis(int n, int excluded, List<String> excludedProfiles, Summary reduction,
                                 Normality normality, Outcome test, String reason, boolean reject,
                                 double meanCiLow, double meanCiHigh, double bootstrapCiLow, double bootstrapCiHigh) {
    }

    public record PairedComparison(Summary difference, Normality normality, String reason, Outcome twoSided,
                                   Outcome oneSidedBLessThanA, boolean rejectTwoSided, boolean rejectOneSided,
                                   double meanCiLow, double meanCiHigh) {
    }

    public record ClashCheck(boolean ok, int profilesWithClashInB, List<String> profiles) {
    }

    public record Validity(double meanA, double meanB, double difference, double ciLow, double ciHigh,
                           boolean warning, double threshold) {
    }

    public record SubgroupRow(String variable, String value, int n, double meanReduction, double medianReduction) {
    }

    public record Result(Params params, int profiles, Map<String, Map<String, Summary>> descriptives,
                         MainHypothesis main, PairedComparison rTotal, ClashCheck clash, Validity validity,
                         List<SubgroupRow> subgroups) {
    }

    private Analysis() {
    }

    public static Result run(List<Pair> pairs, Params params) {
        Map<String, Map<String, Summary>> descriptives = new LinkedHashMap<>();
        descriptives.put("A", describe(pairs, true));
        descriptives.put("B", describe(pairs, false));

        List<Pair> valid = pairs.stream().filter(p -> p.a().tTotal() > 0).toList();
        List<String> excluded = pairs.stream().filter(p -> p.a().tTotal() <= 0).map(Pair::profileId).toList();
        if (valid.size() < 2) {
            throw new IllegalArgumentException("se necesitan al menos 2 perfiles con Ttotal_A > 0 para el análisis; hay "
                    + valid.size());
        }
        MainHypothesis main = mainHypothesis(valid, excluded, params);
        PairedComparison rTotal = rTotal(pairs, params);

        List<String> clashing = pairs.stream().filter(p -> p.b().rTraslape() != 0).map(Pair::profileId).toList();
        ClashCheck clash = new ClashCheck(clashing.isEmpty(), clashing.size(), clashing);

        double[] enrolledDiff = pairs.stream().mapToDouble(p -> p.b().coursesEnrolled() - p.a().coursesEnrolled())
                .toArray();
        double gap = Descriptive.mean(enrolledDiff);
        double[] gapCi = Descriptive.meanConfidenceInterval(enrolledDiff, CONFIDENCE);
        Validity validity = new Validity(
                Descriptive.mean(pairs.stream().mapToDouble(p -> p.a().coursesEnrolled()).toArray()),
                Descriptive.mean(pairs.stream().mapToDouble(p -> p.b().coursesEnrolled()).toArray()),
                gap, gapCi[0], gapCi[1], Math.abs(gap) > ENROLLED_GAP_WARNING, ENROLLED_GAP_WARNING);

        return new Result(params, pairs.size(), descriptives, main, rTotal, clash, validity, subgroups(valid));
    }

    static double reduction(Pair p) {
        return (p.a().tTotal() - p.b().tTotal()) / p.a().tTotal();
    }

    private static Map<String, Summary> describe(List<Pair> pairs, boolean a) {
        Map<String, Summary> out = new LinkedHashMap<>();
        out.put("t_total_s", summary(pairs, a, Observation::tTotal));
        out.put("r_traslape", summary(pairs, a, Observation::rTraslape));
        out.put("r_cupo", summary(pairs, a, Observation::rCupo));
        out.put("r_total", summary(pairs, a, Observation::rTotal));
        out.put("courses_enrolled", summary(pairs, a, Observation::coursesEnrolled));
        return out;
    }

    private static Summary summary(List<Pair> pairs, boolean a, ToDoubleFunction<Observation> f) {
        return Descriptive.summary(pairs.stream().mapToDouble(p -> f.applyAsDouble(a ? p.a() : p.b())).toArray());
    }

    private static Normality normality(String sample, double[] x, Params params) {
        if (x.length < ShapiroWilk.MIN_N) {
            return new Normality(sample, x.length, Double.NaN, Double.NaN, false, false, false);
        }
        ShapiroWilk.Result r = ShapiroWilk.testSubsampling(x, params.seed());
        return new Normality(sample, x.length, r.w(), r.pValue(), r.constant(), r.subsampled(),
                r.normal(params.alpha()));
    }

    private static String reason(Normality n, double alpha) {
        if (n.n() < ShapiroWilk.MIN_N) {
            return "con n = " + n.n() + " no se puede aplicar Shapiro-Wilk; se usa la prueba no paramétrica";
        }
        if (n.constant()) {
            return "la muestra es constante (Shapiro-Wilk no definido); se usa la prueba no paramétrica";
        }
        String sub = n.subsampled() ? " (sobre una submuestra aleatoria de 5000 con semilla fija)" : "";
        return n.normal()
                ? String.format(java.util.Locale.ROOT, "Shapiro-Wilk no rechaza la normalidad (W = %.4f, %s > %s)%s; se usa t",
                n.w(), AnalysisReport.pEq(n.pValue()), alpha, sub)
                : String.format(java.util.Locale.ROOT, "Shapiro-Wilk rechaza la normalidad (W = %.4f, %s <= %s)%s; se usa Wilcoxon",
                n.w(), AnalysisReport.pEq(n.pValue()), alpha, sub);
    }

    private static MainHypothesis mainHypothesis(List<Pair> valid, List<String> excluded, Params params) {
        double[] r = valid.stream().mapToDouble(Analysis::reduction).toArray();
        Normality normality = normality("reducción relativa r", r, params);
        Outcome test = normality.normal()
                ? Tests.tTest(r, params.mu0(), Alternative.GREATER)
                : Tests.wilcoxon(r, params.mu0(), Alternative.GREATER);
        double[] ci = Descriptive.meanConfidenceInterval(r, CONFIDENCE);
        double[] boot = Bootstrap.meanConfidenceInterval(r, CONFIDENCE, params.bootstrapResamples(), params.seed());
        return new MainHypothesis(r.length, excluded.size(), excluded, Descriptive.summary(r), normality, test,
                reason(normality, params.alpha()), test.reject(params.alpha()), ci[0], ci[1], boot[0], boot[1]);
    }

    private static PairedComparison rTotal(List<Pair> pairs, Params params) {
        double[] d = pairs.stream().mapToDouble(p -> p.b().rTotal() - p.a().rTotal()).toArray();
        Normality normality = normality("diferencia Rtotal (B - A)", d, params);
        boolean t = normality.normal();
        Outcome two = t ? Tests.tTest(d, 0, Alternative.TWO_SIDED) : Tests.wilcoxon(d, 0, Alternative.TWO_SIDED);
        Outcome less = t ? Tests.tTest(d, 0, Alternative.LESS) : Tests.wilcoxon(d, 0, Alternative.LESS);
        double[] ci = Descriptive.meanConfidenceInterval(d, CONFIDENCE);
        return new PairedComparison(Descriptive.summary(d), normality, reason(normality, params.alpha()), two, less,
                two.reject(params.alpha()), less.reject(params.alpha()), ci[0], ci[1]);
    }

    private static List<SubgroupRow> subgroups(List<Pair> valid) {
        List<SubgroupRow> rows = new ArrayList<>();
        addGroups(rows, "n_courses", valid, p -> String.format("%03d", p.nCourses()), p -> String.valueOf(p.nCourses()));
        addGroups(rows, "window", valid, p -> String.format("%03d", p.window()), p -> String.valueOf(p.window()));
        addGroups(rows, "program", valid, Pair::program, Pair::program);
        return rows;
    }

    private static void addGroups(List<SubgroupRow> rows, String variable, List<Pair> valid,
                                  java.util.function.Function<Pair, String> sortKey,
                                  java.util.function.Function<Pair, String> label) {
        Map<String, List<Pair>> groups = new TreeMap<>(Comparator.naturalOrder());
        for (Pair p : valid) {
            groups.computeIfAbsent(sortKey.apply(p), k -> new ArrayList<>()).add(p);
        }
        groups.forEach((k, ps) -> {
            double[] r = ps.stream().mapToDouble(Analysis::reduction).toArray();
            rows.add(new SubgroupRow(variable, label.apply(ps.getFirst()), r.length, Descriptive.mean(r),
                    Descriptive.median(r)));
        });
    }
}
