package sched.simulation.experiment;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * CSV de resultados, una fila por perfil y condición. Las columnas finales (withdrawals,
 * r_cupo_in_clash, near_loss) no son variables dependientes: alimentan el informe de calibración.
 */
public final class ResultsCsv {

    static final String HEADER = "profile_id,program,semester,n_courses,window,preference,condition,t_total_s,"
            + "r_traslape,r_cupo,r_total,courses_enrolled,courses_deferred,engine_calls,engine_ms_total,"
            + "withdrawals,r_cupo_in_clash,near_loss";

    private ResultsCsv() {
    }

    /** Una fila por perfil, con la condición A y luego la B. */
    public static String format(List<StudentResult> reactive, List<StudentResult> assisted) {
        StringBuilder out = new StringBuilder(HEADER).append('\n');
        for (int i = 0; i < reactive.size(); i++) {
            out.append(row(reactive.get(i))).append('\n');
            if (i < assisted.size()) {
                out.append(row(assisted.get(i))).append('\n');
            }
        }
        return out.toString();
    }

    private static String row(StudentResult r) {
        Profile p = r.profile();
        return String.format(Locale.ROOT, "%s,%s,%d,%d,%d,%s,%s,%.3f,%d,%d,%d,%d,%d,%d,%.3f,%d,%d,%d",
                p.id(), p.program(), p.semester(), p.courses().size(), p.window(), p.preference().label(),
                r.condition().code(), r.tTotalSeconds(), r.rTraslape(), r.rCupo(), r.rTotal(),
                r.coursesEnrolled(), r.coursesDeferred(), r.engineCalls(), r.engineNanos() / 1e6,
                r.withdrawals(), r.rCupoInClash(), r.nearLoss());
    }

    public static void write(Path path, List<StudentResult> reactive, List<StudentResult> assisted) {
        try {
            Files.writeString(path, format(reactive, assisted));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Lee las columnas que usa la calibración; sirve con cualquier CSV con este encabezado. */
    public static List<ResultRow> read(Path path) {
        try {
            List<String> lines = Files.readAllLines(path);
            List<String> header = Arrays.asList(lines.getFirst().split(","));
            int id = header.indexOf("profile_id");
            int condition = header.indexOf("condition");
            int time = header.indexOf("t_total_s");
            int clash = header.indexOf("r_traslape");
            int seats = header.indexOf("r_cupo");
            int seatsInClash = header.indexOf("r_cupo_in_clash");
            int near = header.indexOf("near_loss");
            if (Arrays.asList(id, condition, time, clash, seats, seatsInClash, near).contains(-1)) {
                throw new IllegalArgumentException("el CSV no tiene las columnas esperadas: " + path);
            }
            List<ResultRow> rows = new ArrayList<>();
            for (String line : lines.subList(1, lines.size())) {
                if (line.isBlank()) {
                    continue;
                }
                String[] c = line.split(",");
                rows.add(new ResultRow(c[id], c[condition], Double.parseDouble(c[time]), Integer.parseInt(c[clash]),
                        Integer.parseInt(c[seats]), Integer.parseInt(c[seatsInClash]), Integer.parseInt(c[near])));
            }
            return rows;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
