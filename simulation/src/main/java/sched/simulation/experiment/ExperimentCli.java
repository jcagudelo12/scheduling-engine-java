package sched.simulation.experiment;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * Subcomando {@code experiment --config <json> --out <dir> [--seed n] [--profiles n]} y
 * {@code calibration --results <csv>}.
 */
public final class ExperimentCli {

    private ExperimentCli() {
    }

    public static void experiment(List<String> args) throws Exception {
        Map<String, String> flags = flags(args, "--config", "--out", "--seed", "--profiles");
        if (!flags.containsKey("--config") || !flags.containsKey("--out")) {
            throw new IllegalArgumentException("uso: experiment --config <json> --out <dir> [--seed n] [--profiles n]");
        }
        ExperimentConfig config = ExperimentConfig.load(Path.of(flags.get("--config")));
        if (flags.containsKey("--seed")) {
            config = config.withSeed(Long.parseLong(flags.get("--seed")));
        }
        if (flags.containsKey("--profiles")) {
            config = config.withProfiles(Integer.parseInt(flags.get("--profiles")));
        }
        Path out = Path.of(flags.get("--out"));
        Files.createDirectories(out);

        long started = System.nanoTime();
        Experiment.Result result = Experiment.run(config);
        double wallSeconds = (System.nanoTime() - started) / 1e9;

        ResultsCsv.write(out.resolve("results.csv"), result.reactive(), result.assisted());
        writeMetadata(out.resolve("metadata.json"), config, flags.get("--config"), result);

        System.out.printf(Locale.ROOT, "experimento: semilla=%d perfiles=%d asignaturas=%d grupos=%d cupos=%d%n",
                config.seed(), config.profiles(), result.setup().offer().courses().size(),
                result.setup().offer().sections().size(), result.setup().offer().totalSeats());
        System.out.printf(Locale.ROOT, "tiempo de ejecución: %.1f s%n", wallSeconds);
        System.out.println(summary(result));
        List<ResultRow> rowsA = result.reactive().stream().map(StudentResult::toRow).toList();
        System.out.print(CalibrationReport.format(
                CalibrationReport.compare(rowsA, result.setup().calibration()), rowsA.size()));
        System.out.println("\nresultados: " + out.resolve("results.csv"));
        System.out.println("metadatos:  " + out.resolve("metadata.json"));
    }

    public static void calibration(List<String> args) {
        Map<String, String> flags = flags(args, "--results", "--calibration");
        if (!flags.containsKey("--results")) {
            throw new IllegalArgumentException("uso: calibration --results <results.csv> [--calibration <json>]");
        }
        List<ResultRow> rows = ResultsCsv.read(Path.of(flags.get("--results")));
        List<ResultRow> rowsA = rows.stream().filter(r -> r.condition().equals("A")).toList();
        System.out.print(CalibrationReport.format(
                CalibrationReport.compare(rows, Calibration.load(flags.get("--calibration"))), rowsA.size()));
    }

    static String summary(Experiment.Result result) {
        StringBuilder out = new StringBuilder();
        out.append(String.format(Locale.ROOT, "%-3s %12s %10s %10s %10s %12s%n", "", "Ttotal (s)", "Rtraslape", "Rcupo", "Rtotal", "engine (ms)"));
        for (List<StudentResult> rows : List.of(result.reactive(), result.assisted())) {
            if (rows.isEmpty()) {
                continue;
            }
            out.append(String.format(Locale.ROOT, "%-3s %12.1f %10.2f %10.2f %10.2f %12.2f%n",
                    rows.getFirst().condition().code(),
                    mean(rows, StudentResult::tTotalSeconds),
                    mean(rows, r -> r.rTraslape()),
                    mean(rows, r -> r.rCupo()),
                    mean(rows, r -> r.rTotal()),
                    mean(rows, r -> r.engineNanos() / 1e6)));
        }
        return out.toString();
    }

    static double mean(List<StudentResult> rows, java.util.function.ToDoubleFunction<StudentResult> f) {
        return rows.stream().mapToDouble(f).average().orElse(Double.NaN);
    }

    private static void writeMetadata(Path path, ExperimentConfig config, String configFile, Experiment.Result result)
            throws IOException {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("seed", config.seed());
        meta.put("profiles", config.profiles());
        meta.put("version", version());
        meta.put("date", Instant.now().toString());
        meta.put("java", System.getProperty("java.version"));
        meta.put("configFile", configFile);
        meta.put("streams", List.of("offer", "profiles", "arrivals", "behavior-time", "behavior-choice"));
        Map<String, Object> offer = new LinkedHashMap<>();
        offer.put("courses", result.setup().offer().courses().size());
        offer.put("sections", result.setup().offer().sections().size());
        offer.put("seats", result.setup().offer().totalSeats());
        offer.put("maxCredits", result.setup().offer().rules().maxCredits());
        meta.put("offer", offer);
        meta.put("config", config);
        JsonMapper mapper = JsonMapper.builder().enable(SerializationFeature.INDENT_OUTPUT).build();
        Files.writeString(path, mapper.writeValueAsString(meta) + "\n");
    }

    /** {@code git describe}, o "desconocida" si no hay git o no es un repositorio. */
    static String version() {
        try {
            Process process = new ProcessBuilder("git", "describe", "--always", "--dirty")
                    .redirectErrorStream(true).start();
            if (!process.waitFor(5, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return "desconocida";
            }
            String text = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            return process.exitValue() == 0 && !text.isEmpty() ? text : "desconocida";
        } catch (IOException | InterruptedException e) {
            return "desconocida";
        }
    }

    private static Map<String, String> flags(List<String> args, String... allowed) {
        Map<String, String> flags = new LinkedHashMap<>();
        for (int i = 0; i < args.size(); i += 2) {
            String name = args.get(i);
            if (!List.of(allowed).contains(name) || i + 1 >= args.size()) {
                throw new IllegalArgumentException("argumento no válido: " + name);
            }
            flags.put(name, args.get(i + 1));
        }
        return flags;
    }
}
