package sched.simulation.analysis;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import sched.simulation.analysis.Analysis.Params;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

/** Subcomando {@code analyze --results <csv> [--out <dir>] [--alpha 0.05] [--mu0 0.20] [--seed n]}. */
public final class AnalysisCli {

    private AnalysisCli() {
    }

    /** @return {@code false} si falla la condición de diseño (Rtraslape en B distinto de 0) */
    public static boolean analyze(List<String> args) throws IOException {
        Map<String, String> flags = new LinkedHashMap<>();
        List<String> allowed = List.of("--results", "--out", "--alpha", "--mu0", "--seed");
        for (int i = 0; i < args.size(); i += 2) {
            if (!allowed.contains(args.get(i)) || i + 1 >= args.size()) {
                throw new IllegalArgumentException("argumento no válido: " + args.get(i));
            }
            flags.put(args.get(i), args.get(i + 1));
        }
        if (!flags.containsKey("--results")) {
            throw new IllegalArgumentException(
                    "uso: analyze --results <csv> [--out <dir>] [--alpha 0.05] [--mu0 0.20] [--seed n]");
        }
        Path results = Path.of(flags.get("--results"));
        Path out = flags.containsKey("--out") ? Path.of(flags.get("--out"))
                : results.toAbsolutePath().getParent();
        Params defaults = Params.defaults();
        Params params = new Params(
                flags.containsKey("--alpha") ? Double.parseDouble(flags.get("--alpha")) : defaults.alpha(),
                flags.containsKey("--mu0") ? Double.parseDouble(flags.get("--mu0")) : defaults.mu0(),
                flags.containsKey("--seed") ? Long.parseLong(flags.get("--seed")) : defaults.seed(),
                defaults.bootstrapResamples());
        return run(results, out, params);
    }

    /** Analiza, imprime el resumen y escribe {@code analysis.md} y {@code analysis.json} en {@code out}. */
    public static boolean run(Path results, Path out, Params params) throws IOException {
        Analysis.Result result = Analysis.run(PairedResults.read(results), params);
        Files.createDirectories(out);
        Files.writeString(out.resolve("analysis.md"), AnalysisReport.markdown(result, results.toString()));
        JsonMapper mapper = JsonMapper.builder().enable(SerializationFeature.INDENT_OUTPUT).build();
        Files.writeString(out.resolve("analysis.json"), mapper.writeValueAsString(result) + "\n");
        System.out.print(AnalysisReport.console(result));
        System.out.println("análisis:   " + out.resolve("analysis.md"));
        System.out.println("            " + out.resolve("analysis.json"));
        return result.clash().ok();
    }
}
