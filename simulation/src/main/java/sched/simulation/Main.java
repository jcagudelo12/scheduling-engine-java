package sched.simulation;

import java.nio.file.Path;
import java.util.Map;
import sched.proto.institution.v1.LoadCatalogResponse;
import sched.proto.institution.v1.SyncStatus;

/**
 * Punto de entrada de las herramientas de simulación.
 *
 * <pre>
 * java -jar simulation/target/sched-simulation.jar simulate [escenario.json]
 * java -jar simulation/target/sched-simulation.jar load [escenario.json] [seq]
 * java -jar simulation/target/sched-simulation.jar seats &lt;seq&gt; &lt;grupo&gt; &lt;cupos&gt;
 * </pre>
 *
 * <p>{@code SCHED_INGEST_URL} indica la ingesta del motor (por defecto {@code localhost:50051}).
 */
public final class Main {

    private static final String DEFAULT_SCENARIO = "simulation/fixtures/sample.json";
    private static final String USAGE = """
            uso:
              simulate [escenario.json]          corre el escenario en memoria, sin red
              load [escenario.json] [seq]        envía el catálogo al motor por gRPC
              seats <seq> <grupo> <cupos>        envía un cambio de cupo al motor
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
            case "load" -> load(scenarioPath(args, 1), args.length > 2 ? Long.parseLong(args[2]) : null);
            case "seats" -> {
                if (args.length != 4) {
                    fail(USAGE);
                }
                seats(Long.parseLong(args[1]), args[2], Integer.parseInt(args[3]));
            }
            default -> fail(USAGE);
        }
    }

    private static void simulate(Path path) throws Exception {
        Map<String, Simulation.Result> results = Simulation.run(Scenario.load(path));
        System.out.println("escenario: " + path);
        System.out.printf("%-12s %13s %12s%n", "estudiante", "combinaciones", "tiempo (µs)");
        results.forEach((student, r) -> System.out.printf("%-12s %13d %12d%n", student, r.combinations(), r.micros()));
    }

    private static void load(Path path, Long seq) throws Exception {
        Scenario scenario = Scenario.load(path);
        try (FakeInstitution institution = new FakeInstitution(ingestUrl())) {
            LoadCatalogResponse response = institution.load(seq != null ? seq : scenario.catalogSeq(), scenario.sections());
            System.out.printf("catálogo cargado: seq=%d grupos=%d%n", response.getSeq(), response.getSectionsLoaded());
        }
    }

    private static void seats(long seq, String section, int available) throws Exception {
        try (FakeInstitution institution = new FakeInstitution(ingestUrl())) {
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

    private static void fail(String message) {
        System.err.print(message);
        System.exit(2);
    }
}
