package sched.simulation.analysis;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Lee el CSV del experimento y empareja, por {@code profile_id}, la condición A con la B. */
public final class PairedResults {

    /** Variables dependientes de una condición para un perfil. */
    public record Observation(double tTotal, int rTraslape, int rCupo, int rTotal, int coursesEnrolled) {
    }

    /** Un perfil con sus resultados en A (reactivo) y B (asistido). */
    public record Pair(String profileId, String program, int semester, int nCourses, int window,
                       Observation a, Observation b) {
    }

    private PairedResults() {
    }

    /** Empareja los resultados de una corrida (las listas van en el mismo orden de perfiles). */
    public static List<Pair> of(List<sched.simulation.experiment.StudentResult> reactive,
                                List<sched.simulation.experiment.StudentResult> assisted) {
        if (reactive.size() != assisted.size()) {
            throw new IllegalArgumentException("las dos condiciones deben tener los mismos perfiles");
        }
        List<Pair> pairs = new ArrayList<>();
        for (int i = 0; i < reactive.size(); i++) {
            sched.simulation.experiment.StudentResult a = reactive.get(i);
            sched.simulation.experiment.StudentResult b = assisted.get(i);
            sched.simulation.experiment.Profile p = a.profile();
            pairs.add(new Pair(p.id(), p.program(), p.semester(), p.courses().size(), p.window(), observation(a),
                    observation(b)));
        }
        return pairs;
    }

    private static Observation observation(sched.simulation.experiment.StudentResult r) {
        return new Observation(r.tTotalSeconds(), r.rTraslape(), r.rCupo(), r.rTotal(), r.coursesEnrolled());
    }

    public static List<Pair> read(Path path) {
        try {
            return parse(Files.readAllLines(path), path.toString());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** @param source nombre para los mensajes de error */
    public static List<Pair> parse(List<String> lines, String source) {
        if (lines.isEmpty()) {
            throw new IllegalArgumentException("el CSV está vacío: " + source);
        }
        List<String> header = Arrays.asList(lines.getFirst().trim().split(","));
        String[] names = {"profile_id", "program", "semester", "n_courses", "window", "condition", "t_total_s",
                "r_traslape", "r_cupo", "r_total", "courses_enrolled"};
        int[] col = new int[names.length];
        for (int i = 0; i < names.length; i++) {
            col[i] = header.indexOf(names[i]);
            if (col[i] < 0) {
                throw new IllegalArgumentException("al CSV le falta la columna '" + names[i] + "': " + source);
            }
        }
        Map<String, Pair> pairs = new LinkedHashMap<>();
        Map<String, Observation> a = new LinkedHashMap<>();
        Map<String, Observation> b = new LinkedHashMap<>();
        Map<String, String[]> meta = new LinkedHashMap<>();
        for (int n = 1; n < lines.size(); n++) {
            String line = lines.get(n);
            if (line.isBlank()) {
                continue;
            }
            String[] c = line.trim().split(",", -1);
            if (c.length < header.size()) {
                throw new IllegalArgumentException("fila " + (n + 1) + " con menos columnas que el encabezado: " + source);
            }
            String id = c[col[0]];
            String condition = c[col[5]];
            Observation obs;
            try {
                obs = new Observation(Double.parseDouble(c[col[6]]), Integer.parseInt(c[col[7]]),
                        Integer.parseInt(c[col[8]]), Integer.parseInt(c[col[9]]), Integer.parseInt(c[col[10]]));
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("fila " + (n + 1) + " con un valor numérico no válido: " + source, e);
            }
            Map<String, Observation> target = switch (condition) {
                case "A" -> a;
                case "B" -> b;
                default -> throw new IllegalArgumentException(
                        "fila " + (n + 1) + ": condición '" + condition + "' desconocida (se espera A o B)");
            };
            if (target.put(id, obs) != null) {
                throw new IllegalArgumentException("el perfil " + id + " aparece más de una vez en la condición " + condition);
            }
            meta.putIfAbsent(id, new String[] {c[col[1]], c[col[2]], c[col[3]], c[col[4]]});
        }
        for (String id : meta.keySet()) {
            if (!a.containsKey(id) || !b.containsKey(id)) {
                throw new IllegalArgumentException("el perfil " + id + " no tiene pareja: falta la condición "
                        + (a.containsKey(id) ? "B" : "A"));
            }
            String[] m = meta.get(id);
            pairs.put(id, new Pair(id, m[0], Integer.parseInt(m[1]), Integer.parseInt(m[2]),
                    Integer.parseInt(m[3]), a.get(id), b.get(id)));
        }
        if (pairs.isEmpty()) {
            throw new IllegalArgumentException("el CSV no tiene filas de datos: " + source);
        }
        return new ArrayList<>(pairs.values());
    }
}
