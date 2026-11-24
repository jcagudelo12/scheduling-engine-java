package sched.simulation.experiment;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.random.RandomGenerator;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Distribuciones de la consulta a 135 estudiantes (Anexo A). Programa, semestre y número de
 * asignaturas son entradas para generar perfiles; A9, A11 y A13 son objetivos que el flujo A
 * debe reproducir (ver {@link CalibrationReport}).
 */
public final class Calibration {

    static final String DEFAULT_RESOURCE = "/calibration/consulta-2026-2.json";

    /**
     * Categoría de una tabla. {@code min}/{@code max} son enteros inclusivos ({@code max} nulo =
     * abierto) y {@code maxMinutes} el límite superior de A11 ({@code null} = abierto).
     */
    public record Category(
            String id, String label, double percent, Integer min, Integer max,
            Double maxMinutes, boolean maxInclusive) {
    }

    private final JsonNode root;

    private Calibration(JsonNode root) {
        this.root = root;
    }

    public static Calibration load(String file) {
        try {
            if (file == null || file.isBlank()) {
                try (InputStream in = Calibration.class.getResourceAsStream(DEFAULT_RESOURCE)) {
                    if (in == null) {
                        throw new IllegalStateException("falta el recurso " + DEFAULT_RESOURCE);
                    }
                    return new Calibration(JsonMapper.builder().build().readTree(in));
                }
            }
            return new Calibration(JsonMapper.builder().build().readTree(Path.of(file)));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public int surveySize() {
        return root.path("n").asInt();
    }

    public List<Category> programs() {
        return categories("programa");
    }

    public List<Category> semesters() {
        return categories("semestre");
    }

    public List<Category> courseCounts() {
        return categories("n_asignaturas");
    }

    /** A9: veces que rehizo la selección por cruces. */
    public List<Category> redoneSelection() {
        return categories("rehizo_seleccion");
    }

    /** A11: tiempo en la plataforma hasta confirmar. */
    public List<Category> platformTime() {
        return categories("tiempo_plataforma");
    }

    /** A13: perdió o casi perdió cupo mientras resolvía un cruce (ids {@code si}, {@code casi}, {@code no}). */
    public List<Category> lostSeat() {
        return categories("perdio_cupo");
    }

    private List<Category> categories(String table) {
        List<Category> result = new ArrayList<>();
        for (JsonNode node : root.path(table).path("categorias")) {
            result.add(new Category(
                    node.has("id") ? node.get("id").asString() : null,
                    node.get("etiqueta").asString(),
                    node.get("porcentaje").asDouble(),
                    node.hasNonNull("min") ? node.get("min").asInt() : null,
                    node.hasNonNull("max") ? node.get("max").asInt() : null,
                    node.hasNonNull("max_minutos") ? node.get("max_minutos").asDouble() : null,
                    !node.has("max_inclusivo") || node.get("max_inclusivo").asBoolean()));
        }
        if (result.isEmpty()) {
            throw new IllegalStateException("la calibración no trae la tabla " + table);
        }
        return result;
    }

    /** Categoría elegida con probabilidad proporcional a su porcentaje (se normaliza: pueden sumar 100,1). */
    static Category pick(RandomGenerator random, List<Category> categories) {
        double[] weights = categories.stream().mapToDouble(Category::percent).toArray();
        return categories.get(Seeds.pick(random, weights));
    }

    /** Entero uniforme dentro del rango de una categoría. */
    static int uniformIn(RandomGenerator random, Category category) {
        return category.min() + random.nextInt(category.max() - category.min() + 1);
    }
}
