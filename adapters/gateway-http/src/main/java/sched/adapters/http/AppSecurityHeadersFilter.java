package sched.adapters.http;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.core.Ordered;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Cabeceras de seguridad de las páginas del cliente web ({@code /app/**}): política de contenido
 * estricta (solo recursos del propio origen, sin scripts ni estilos en línea), sin adivinar tipos
 * MIME y sin enviar el origen de referencia. No toca las respuestas de la API.
 */
public final class AppSecurityHeadersFilter extends OncePerRequestFilter implements Ordered {

    /** Sin {@code unsafe-inline}: los scripts y estilos van en archivos propios. */
    static final String CONTENT_SECURITY_POLICY = "default-src 'self'; script-src 'self'; style-src 'self'; "
            + "img-src 'self'; connect-src 'self'; object-src 'none'; base-uri 'none'; form-action 'none'; "
            + "frame-ancestors 'none'";

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 50;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return !("/app".equals(path) || path.startsWith("/app/"));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        response.setHeader("Content-Security-Policy", CONTENT_SECURITY_POLICY);
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setHeader("Referrer-Policy", "no-referrer");
        response.setHeader("Cache-Control", "no-cache");
        chain.doFilter(request, response);
    }
}
