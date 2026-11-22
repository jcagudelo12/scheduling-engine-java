package sched.adapters.http;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Set;

/**
 * Rutas que se sirven sin token incluso con {@code SCHED_AUTH=jwt}. La lista es explícita y mínima:
 * las sondas ({@code /health}, {@code /ready}) y el cliente web de demostración, que son recursos
 * estáticos sin datos de ningún estudiante ({@code /app} y {@code /app/**}, solo {@code GET} y
 * {@code HEAD}). Todo lo demás, incluida la API, exige el token.
 */
final class PublicPaths {

    private static final Set<String> PROBES = Set.of("/health", "/ready");
    private static final String APP = "/app";
    private static final String APP_PREFIX = "/app/";

    private PublicPaths() {
    }

    /** Sondas de salud: públicas con cualquier método, como siempre. */
    static boolean isProbe(HttpServletRequest request) {
        return PROBES.contains(request.getRequestURI());
    }

    /** El cliente web: {@code /app}, {@code /app/} y lo que cuelga de {@code /app/}, solo para lectura. */
    static boolean isApp(HttpServletRequest request) {
        String method = request.getMethod();
        if (!"GET".equals(method) && !"HEAD".equals(method)) {
            return false;
        }
        String path = request.getRequestURI();
        if (!APP.equals(path) && !path.startsWith(APP_PREFIX)) {
            return false;
        }
        // Nada que se pueda leer como otra ruta tras decodificar o normalizar: ante la duda, exige token.
        return !(path.contains("..") || path.contains("%") || path.contains(";") || path.contains("\\")
                || path.contains("//"));
    }

    static boolean isPublic(HttpServletRequest request) {
        return isProbe(request) || isApp(request);
    }
}
