package sched.simulation.analysis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AnalysisTest {

    private static final String HEADER = "profile_id,program,semester,n_courses,window,preference,condition,t_total_s,"
            + "r_traslape,r_cupo,r_total,courses_enrolled,courses_deferred,engine_calls,engine_ms_total,"
            + "withdrawals,r_cupo_in_clash,near_loss";

    private static String row(String id, String cond, double t, int clash, int seats, int enrolled) {
        return String.format(java.util.Locale.ROOT, "%s,SIS,%d,%d,%d,manana,%s,%.3f,%d,%d,%d,%d,0,0,0.0,0,0,0",
                id, 3, 4 + id.length() % 2, 1, cond, t, clash, seats, clash + seats, enrolled);
    }

    /** Ocho perfiles con Ttotal_A = 100 y reducciones de 30 % a 60 %. */
    private static List<String> sample(int clashInB) {
        double[] tb = {50, 55, 60, 65, 70, 40, 45, 52};
        List<String> lines = new ArrayList<>(List.of(HEADER));
        for (int i = 0; i < tb.length; i++) {
            String id = "p" + i;
            lines.add(row(id, "A", 100, 0, i % 2, 4));
            lines.add(row(id, "B", tb[i], i == 0 ? clashInB : 0, 0, 4));
        }
        return lines;
    }

    @Test
    void empareLasCondicionesYCalculaLaReduccion() {
        List<PairedResults.Pair> pairs = PairedResults.parse(sample(0), "prueba");
        assertEquals(8, pairs.size());
        assertEquals(0.5, Analysis.reduction(pairs.getFirst()), 1e-12);
        Analysis.Result r = Analysis.run(pairs, Analysis.Params.defaults());
        double[] reductions = {0.5, 0.45, 0.4, 0.35, 0.3, 0.6, 0.55, 0.48};
        assertEquals(Descriptive.mean(reductions), r.main().reduction().mean(), 1e-12);
        assertTrue(r.main().reject()); // todas las reducciones superan 0,20
        assertTrue(r.clash().ok());
        assertEquals(0, r.validity().difference(), 1e-12);
        assertFalse(r.validity().warning());
        // Rtotal: B no es peor que A en ningún perfil
        assertTrue(r.rTotal().difference().mean() < 0);
        assertFalse(r.subgroups().isEmpty());
    }

    @Test
    void unPerfilSinParejaEsUnErrorClaro() {
        List<String> lines = sample(0);
        lines.removeLast(); // quita la fila B del último perfil
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> PairedResults.parse(lines, "prueba"));
        assertTrue(e.getMessage().contains("p7") && e.getMessage().contains("pareja"));
    }

    @Test
    void marcaFallaSiBTieneTraslapes() {
        Analysis.Result r = Analysis.run(PairedResults.parse(sample(2), "prueba"), Analysis.Params.defaults());
        assertFalse(r.clash().ok());
        assertEquals(List.of("p0"), r.clash().profiles());
        assertTrue(AnalysisReport.markdown(r, "x.csv").contains("**FALLA**"));
    }

    @Test
    void excluyeLosPerfilesConTtotalACero() {
        List<String> lines = sample(0);
        lines.add(row("pz", "A", 0, 0, 0, 4));
        lines.add(row("pz", "B", 0, 0, 0, 4));
        Analysis.Result r = Analysis.run(PairedResults.parse(lines, "prueba"), Analysis.Params.defaults());
        assertEquals(9, r.profiles());
        assertEquals(1, r.main().excluded());
        assertEquals(8, r.main().n());
    }

    @Test
    void escribeAnalysisMdYJson(@TempDir Path dir) throws Exception {
        Path csv = dir.resolve("results.csv");
        Files.write(csv, sample(0));
        assertTrue(AnalysisCli.analyze(List.of("--results", csv.toString(), "--out", dir.toString())));
        String md = Files.readString(dir.resolve("analysis.md"));
        assertTrue(md.contains("Se rechaza H0"));
        assertTrue(md.contains("Prueba elegida"));
        assertTrue(md.contains("bootstrap"));
        assertTrue(Files.readString(dir.resolve("analysis.json")).contains("\"reject\""));
    }
}
