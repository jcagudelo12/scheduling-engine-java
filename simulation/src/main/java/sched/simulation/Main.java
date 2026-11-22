package sched.simulation;

import io.grpc.ChannelCredentials;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import sched.simulation.analysis.AnalysisCli;
import sched.simulation.calibration.CalibrationCli;
import sched.simulation.experiment.DemoCatalog;
import sched.simulation.experiment.ExperimentCli;
import sched.simulation.sensitivity.SensitivityCli;
import sched.proto.institution.v1.LoadCatalogResponse;
import sched.proto.institution.v1.SyncStatus;

/**
 * Punto de entrada de las herramientas de simulación.
 *
 * <pre>
 * java -jar simulation/target/sched-simulation.jar simulate [escenario.json]
 * java -jar simulation/target/sched-simulation.jar load [escenario.json] [seq]
 * java -jar simulation/target/sched-simulation.jar seats &lt;seq&gt; &lt;grupo&gt; &lt;cupos&gt;
 * java -jar simulation/target/sched-simulation.jar demo-catalog --out &lt;scenario.json&gt; [--seed n]
 * java -jar simulation/target/sched-simulation.jar experiment --config &lt;json&gt; --out &lt;dir&gt; [--seed n] [--profiles n] [--analyze]
 * java -jar simulation/target/sched-simulation.jar calibrate --config &lt;json&gt; --out &lt;dir&gt; [--budget n] [--profiles n] [--seed n]
 * java -jar simulation/target/sched-simulation.jar sensitivity --config &lt;json&gt; --out &lt;dir&gt; [--profiles n] [--replications r]
 * java -jar simulation/target/sched-simulation.jar calibration --results &lt;results.csv&gt;
 * java -jar simulation/target/sched-simulation.jar analyze --results &lt;results.csv&gt; [--out &lt;dir&gt;] [--alpha 0.05] [--mu0 0.20] [--seed n]
 * </pre>
 *
 * <p>{@code SCHED_INGEST_URL} indica la ingesta del motor (por defecto {@code localhost:50051}).
 *
 * <p>La ingesta exige TLS con autenticación mutua: {@code load} y {@code seats} necesitan el
 * certificado de cliente ({@code --tls-cert}, {@code --tls-key}) y la CA del servidor ({@code --tls-ca}),
 * o las variables {@code SCHED_INGEST_TLS_CERT}, {@code SCHED_INGEST_TLS_KEY} y
 * {@code SCHED_INGEST_TLS_CA}. Contra un motor con {@code SCHED_GRPC_TLS=disabled} se usa
 * {@code --plaintext} o {@code SCHED_INGEST_TLS=disabled}.
 *
 * <p>Token firmado para probar la API HTTP (ADR 0004): {@code keygen} y {@code token}.
 */
public final class Main {

    private static final String DEFAULT_SCENARIO = "simulation/fixtures/sample.json";
    private static final String USAGE = """
            uso:
              simulate [escenario.json]          corre el escenario en memoria, sin red
              load [escenario.json] [seq]        envía el catálogo al motor por gRPC
              seats <seq> <grupo> <cupos>        envía un cambio de cupo al motor
                (load y seats aceptan --tls-cert <pem> --tls-key <pem> --tls-ca <pem> o --plaintext)
              keygen --out <dir> --kid <kid>     genera un par Ed25519 (<kid>.key privada, <kid>.pem pública)
              token --key <pem> --kid <kid> --student <id> --courses A,B --iss <emisor> --aud <audiencia> [--ttl s]
                                                 imprime un token firmado para Authorization: Bearer (--ttl: 600 s)
              demo-catalog --out <scenario.json> [--seed n]
                                                 exporta una oferta sintética (estilo ITM) para el cliente web; cárgala con load
              experiment --config <json> --out <dir> [--seed n] [--profiles n] [--analyze]
                                                 experimento pareado A (reactivo) frente a B (asistido)
              calibrate --config <json> --out <dir> [--budget n] [--profiles n] [--seed n]
                                                 busca los parámetros del flujo A que reproducen la consulta
                                                 (calibrated.json, calibration.md)
              sensitivity --config <json> --out <dir> [--profiles n] [--replications r]
                                                 sensibilidad de la conclusión principal (sensitivity.md y .json)
              analyze --results <csv> [--out <dir>] [--alpha 0.05] [--mu0 0.20] [--seed n]
                                                 análisis estadístico del experimento (analysis.md y analysis.json)
              calibration --results <csv> [--calibration <json>]
                                                 distribuciones simuladas de A frente a la consulta
            """;

    private Main() {
    }

    public static void main(String[] args) {
        try {
            run(args);
        } catch (Exception e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            System.err.println("error: " + cause.getMessage());
            System.exit(1);
        }
    }

    private static void run(String[] args) throws Exception {
        if (args.length == 0) {
            fail(USAGE);
        }
        switch (args[0]) {
            case "simulate" -> simulate(scenarioPath(args, 1));
            case "load" -> {
                Cli cli = Cli.parse(args);
                load(scenarioPath(cli.positional(), 1),
                        cli.positional().length > 2 ? Long.parseLong(cli.positional()[2]) : null, cli.credentials());
            }
            case "seats" -> {
                Cli cli = Cli.parse(args);
                if (cli.positional().length != 4) {
                    fail(USAGE);
                }
                seats(Long.parseLong(cli.positional()[1]), cli.positional()[2],
                        Integer.parseInt(cli.positional()[3]), cli.credentials());
            }
            case "keygen" -> {
                Cli cli = Cli.parse(args);
                DevAuth.keygen(Path.of(cli.required("--out")), cli.required("--kid"));
                System.out.println("llaves generadas en " + cli.required("--out") + ": " + cli.required("--kid")
                        + ".key (privada, no la comparta) y " + cli.required("--kid") + ".pem (pública, para el motor)");
            }
            case "token" -> {
                Cli cli = Cli.parse(args);
                System.out.println(DevAuth.token(Path.of(cli.required("--key")), cli.required("--kid"),
                        cli.required("--student"), Arrays.asList(cli.required("--courses").split(",")),
                        cli.required("--iss"), cli.required("--aud"),
                        Long.parseLong(cli.options().getOrDefault("--ttl", "600")), Instant.now()));
            }
            case "demo-catalog" -> {
                Cli cli = Cli.parse(args);
                System.out.println(DemoCatalog.write(Path.of(cli.required("--out")),
                        Long.parseLong(cli.options().getOrDefault("--seed", "42"))));
            }
            case "experiment" -> designHolds(ExperimentCli.experiment(List.of(args).subList(1, args.length)));
            case "analyze" -> designHolds(AnalysisCli.analyze(List.of(args).subList(1, args.length)));
            case "calibrate" -> CalibrationCli.calibrate(List.of(args).subList(1, args.length));
            case "sensitivity" -> SensitivityCli.sensitivity(List.of(args).subList(1, args.length));
            case "calibration" -> ExperimentCli.calibration(List.of(args).subList(1, args.length));
            default -> fail(USAGE);
        }
    }

    /** Rtraslape en B debe ser 0: si no, el comando termina con código distinto de 0. */
    private static void designHolds(boolean holds) {
        if (!holds) {
            System.err.println("FALLA de la condición de diseño: Rtraslape en B distinto de 0 (ver analysis.md)");
            System.exit(3);
        }
    }

    private static void simulate(Path path) throws Exception {
        Map<String, Simulation.Result> results = Simulation.run(Scenario.load(path));
        System.out.println("escenario: " + path);
        System.out.printf("%-12s %13s %12s%n", "estudiante", "combinaciones", "tiempo (µs)");
        results.forEach((student, r) -> System.out.printf("%-12s %13d %12d%n", student, r.combinations(), r.micros()));
    }

    private static void load(Path path, Long seq, ChannelCredentials credentials) throws Exception {
        Scenario scenario = Scenario.load(path);
        try (FakeInstitution institution = new FakeInstitution(ingestUrl(), credentials)) {
            LoadCatalogResponse response = institution.load(
                    seq != null ? seq : scenario.catalogSeq(), scenario.sections(), scenario.courses(), scenario.rules());
            System.out.printf("catálogo cargado: seq=%d grupos=%d%n", response.getSeq(), response.getSectionsLoaded());
        }
    }

    private static void seats(long seq, String section, int available, ChannelCredentials credentials)
            throws Exception {
        try (FakeInstitution institution = new FakeInstitution(ingestUrl(), credentials)) {
            SyncStatus status = institution.seats(seq, section, available);
            String text = switch (status.getStatusCase()) {
                case ACK -> "ack seq=" + status.getAck().getSeq();
                case RESYNC_REQUIRED -> "resync_required esperado=" + status.getResyncRequired().getExpectedSeq()
                        + " recibido=" + status.getResyncRequired().getReceivedSeq();
                case REJECTED -> "rejected seq=" + status.getRejected().getSeq() + ": " + status.getRejected().getReason();
                case STATUS_NOT_SET -> "sin estado";
            };
            System.out.println("respuesta del motor: " + text);
        }
    }

    private static Path scenarioPath(String[] args, int index) {
        return Path.of(args.length > index ? args[index] : DEFAULT_SCENARIO);
    }

    private static String ingestUrl() {
        String url = System.getenv("SCHED_INGEST_URL");
        return url == null || url.isBlank() ? "localhost:50051" : url;
    }

    /**
     * Opciones {@code --nombre valor} (y {@code --plaintext}, sin valor) mezcladas con los argumentos
     * posicionales; la primera palabra, el subcomando, queda en {@code positional[0]}.
     */
    private record Cli(String[] positional, Map<String, String> options) {

        static Cli parse(String[] args) {
            List<String> positional = new ArrayList<>();
            Map<String, String> options = new HashMap<>();
            for (int i = 0; i < args.length; i++) {
                if (args[i].equals("--plaintext")) {
                    options.put("--plaintext", "true");
                } else if (args[i].startsWith("--")) {
                    if (i + 1 >= args.length) {
                        throw new IllegalArgumentException("falta el valor de " + args[i]);
                    }
                    options.put(args[i], args[++i]);
                } else {
                    positional.add(args[i]);
                }
            }
            return new Cli(positional.toArray(String[]::new), options);
        }

        String required(String name) {
            String value = options.get(name);
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException("falta " + name);
            }
            return value;
        }

        /** Opción de la línea de comandos, o la variable de entorno si no se indicó. */
        private String option(String name, String variable) {
            String value = options.get(name);
            return value != null ? value : System.getenv(variable);
        }

        /** mTLS por defecto, como lo exige el motor; sin TLS solo si se pide explícitamente. */
        ChannelCredentials credentials() throws java.io.IOException {
            String env = System.getenv("SCHED_INGEST_TLS");
            if (options.containsKey("--plaintext") || "disabled".equals(env)) {
                System.err.println("aviso: conexión SIN TLS (solo contra un motor con SCHED_GRPC_TLS=disabled)");
                return io.grpc.InsecureChannelCredentials.create();
            }
            String cert = option("--tls-cert", "SCHED_INGEST_TLS_CERT");
            String key = option("--tls-key", "SCHED_INGEST_TLS_KEY");
            String ca = option("--tls-ca", "SCHED_INGEST_TLS_CA");
            if (cert == null || key == null || ca == null) {
                throw new IllegalArgumentException("la ingesta exige TLS con autenticación mutua: indique "
                        + "--tls-cert, --tls-key y --tls-ca (o SCHED_INGEST_TLS_CERT, _KEY y _CA); "
                        + "con un motor sin TLS (SCHED_GRPC_TLS=disabled) use --plaintext");
            }
            return FakeInstitution.mutualTls(Path.of(cert), Path.of(key), Path.of(ca));
        }
    }

    private static void fail(String message) {
        System.err.print(message);
        System.exit(2);
    }
}
