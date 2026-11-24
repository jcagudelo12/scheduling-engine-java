package sched.simulation.sensitivity;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import sched.simulation.analysis.Power;
import sched.simulation.experiment.ExperimentConfig;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * Subcomando {@code sensitivity --config <json> --out <dir> [--profiles n] [--replications r]}:
 * análisis de sensibilidad de la conclusión principal (reducción de Ttotal frente a H0: r &lt;= 20 %)
 * a los supuestos del simulador, sobre todo a los tiempos de B (que no tienen datos empíricos).
 * Escribe {@code sensitivity.md} y {@code sensitivity.json}.
 */
public final class SensitivityCli {

    public static final int DEFAULT_REPLICATIONS = 10;

    private SensitivityCli() {
    }

    /** Resultado completo, para el reporte y los tests. */
    public record Outcome(ExperimentConfig config, List<SensitivityRunner.Row> rows,
                          SensitivityRunner.Breakpoint breakpoint, SensitivityRunner.BrowseBreakpoint browseBreakpoint,
                          SensitivityRunner.Replicas replicas,
                          Power.Check power, int recommendedProfiles) {
    }

    public static void sensitivity(List<String> args) throws IOException {
        Map<String, String> flags = new LinkedHashMap<>();
        List<String> allowed = List.of("--config", "--out", "--profiles", "--replications");
        for (int i = 0; i < args.size(); i += 2) {
            if (!allowed.contains(args.get(i)) || i + 1 >= args.size()) {
                throw new IllegalArgumentException("argumento no válido: " + args.get(i));
            }
            flags.put(args.get(i), args.get(i + 1));
        }
        if (!flags.containsKey("--config") || !flags.containsKey("--out")) {
            throw new IllegalArgumentException(
                    "uso: sensitivity --config <json> --out <dir> [--profiles n] [--replications r]");
        }
        ExperimentConfig config = ExperimentConfig.load(Path.of(flags.get("--config")));
        if (flags.containsKey("--profiles")) {
            config = config.withProfiles(Integer.parseInt(flags.get("--profiles")));
        }
        int replications = flags.containsKey("--replications")
                ? Integer.parseInt(flags.get("--replications")) : DEFAULT_REPLICATIONS;
        Path out = Path.of(flags.get("--out"));
        Files.createDirectories(out);

        long started = System.nanoTime();
        ExecutorService pool = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors());
        Outcome outcome;
        try {
            outcome = run(config, Variations.standard(), replications, pool);
        } finally {
            pool.shutdownNow();
        }
        double seconds = (System.nanoTime() - started) / 1e9;
        write(out, outcome, flags.get("--config"), seconds);
        System.out.print(SensitivityMarkdown.console(outcome));
        System.out.printf(Locale.ROOT, "sensibilidad: %.1f s%n", seconds);
        System.out.println("reporte: " + out.resolve("sensitivity.md"));
        System.out.println("         " + out.resolve("sensitivity.json"));
    }

    public static Outcome run(ExperimentConfig config, List<Variation> variations, int replications,
                              ExecutorService pool) {
        List<SensitivityRunner.Row> rows = SensitivityRunner.runAll(config, variations, pool);
        SensitivityRunner.Replicas replicas = SensitivityRunner.replicate(config, replications, pool);
        SensitivityRunner.Breakpoint breakpoint = SensitivityRunner.breakpoint(config, SensitivityRunner.MU0);
        SensitivityRunner.BrowseBreakpoint browse = SensitivityRunner.browseBreakpoint(config, SensitivityRunner.MU0);
        double sd = rows.getFirst().sdReduction();
        Power.Check power = Power.check(sd, config.profiles(), SensitivityRunner.ALPHA, Power.MIN_EFFECT);
        int recommended = Math.max(500, (int) (Math.ceil(power.requiredFor90() / 100.0) * 100));
        return new Outcome(config, rows, breakpoint, browse, replicas, power, recommended);
    }

    public static void write(Path out, Outcome outcome, String configFile, double seconds) throws IOException {
        Files.writeString(out.resolve("sensitivity.md"), SensitivityMarkdown.markdown(outcome, configFile, seconds));
        JsonMapper mapper = JsonMapper.builder().enable(SerializationFeature.INDENT_OUTPUT).build();
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("seed", outcome.config().seed());
        json.put("profiles", outcome.config().profiles());
        json.put("mu0", SensitivityRunner.MU0);
        json.put("alpha", SensitivityRunner.ALPHA);
        json.put("variations", outcome.rows());
        json.put("breakpoint", outcome.breakpoint());
        json.put("browseBreakpoint", outcome.browseBreakpoint());
        json.put("replicas", outcome.replicas());
        json.put("power", outcome.power());
        json.put("recommendedProfiles", outcome.recommendedProfiles());
        Files.writeString(out.resolve("sensitivity.json"), mapper.writeValueAsString(json) + "\n");
    }
}
