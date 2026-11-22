package sched.simulation.calibration;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import sched.simulation.experiment.Calibration;
import sched.simulation.experiment.ExperimentConfig;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * Subcomando {@code calibrate --config <json> --out <dir> [--budget n] [--profiles n] [--seed n]}:
 * busca los parámetros del flujo A (y los compartidos) que acercan A9, A11 y A13 simulados a la
 * consulta. Escribe {@code calibrated.json} (configuración completa lista para {@code experiment}),
 * {@code calibration.md} y {@code calibration.json}.
 */
public final class CalibrationCli {

    /** Evaluaciones por defecto: unos 40-60 s en una máquina de 16 núcleos. */
    public static final int DEFAULT_BUDGET = 8000;

    private CalibrationCli() {
    }

    public static void calibrate(List<String> args) throws IOException {
        Map<String, String> flags = new LinkedHashMap<>();
        List<String> allowed = List.of("--config", "--out", "--budget", "--profiles", "--seed");
        for (int i = 0; i < args.size(); i += 2) {
            if (!allowed.contains(args.get(i)) || i + 1 >= args.size()) {
                throw new IllegalArgumentException("argumento no válido: " + args.get(i));
            }
            flags.put(args.get(i), args.get(i + 1));
        }
        if (!flags.containsKey("--config") || !flags.containsKey("--out")) {
            throw new IllegalArgumentException(
                    "uso: calibrate --config <json> --out <dir> [--budget n] [--profiles n] [--seed n]");
        }
        ExperimentConfig base = ExperimentConfig.load(Path.of(flags.get("--config")));
        if (flags.containsKey("--seed")) {
            base = base.withSeed(Long.parseLong(flags.get("--seed")));
        }
        if (flags.containsKey("--profiles")) {
            base = base.withProfiles(Integer.parseInt(flags.get("--profiles")));
        }
        int budget = flags.containsKey("--budget") ? Integer.parseInt(flags.get("--budget")) : DEFAULT_BUDGET;
        Path out = Path.of(flags.get("--out"));
        Files.createDirectories(out);

        long started = System.nanoTime();
        Calibration calibration = Calibration.load(base.calibrationFile());
        ExecutorService pool = Search.newPool(Runtime.getRuntime().availableProcessors());
        Calibrator.Outcome outcome;
        try {
            outcome = Calibrator.calibrate(base, calibration, ParameterSpace.defaults(), budget, pool);
        } finally {
            pool.shutdownNow();
        }
        double seconds = (System.nanoTime() - started) / 1e9;

        write(out, outcome, flags.get("--config"), budget, seconds);
        System.out.printf(Locale.ROOT, "calibración: %d evaluaciones en %.1f s (semilla %d, %d perfiles)%n",
                outcome.search().evaluations(), seconds, base.seed(), base.profiles());
        System.out.print(CalibrationMarkdown.console(outcome));
        System.out.println("configuración calibrada: " + out.resolve("calibrated.json"));
        System.out.println("reporte:                 " + out.resolve("calibration.md"));
    }

    /** Escribe {@code calibrated.json}, {@code calibration.md} y {@code calibration.json}. */
    public static void write(Path out, Calibrator.Outcome outcome, String configFile, int budget, double seconds)
            throws IOException {
        JsonMapper mapper = JsonMapper.builder().enable(SerializationFeature.INDENT_OUTPUT).build();
        Files.writeString(out.resolve("calibrated.json"), mapper.writeValueAsString(outcome.calibrated()) + "\n");
        Files.writeString(out.resolve("calibration.md"),
                CalibrationMarkdown.markdown(outcome, configFile, budget, seconds));
        Files.writeString(out.resolve("calibration.json"),
                mapper.writeValueAsString(CalibrationMarkdown.summary(outcome, budget)) + "\n");
    }
}
