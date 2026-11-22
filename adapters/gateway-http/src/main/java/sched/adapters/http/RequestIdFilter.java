package sched.adapters.http;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Id de correlación de la solicitud: acepta {@code X-Request-Id} si trae una forma segura, y si no
 * (ausente, vacío, demasiado largo o con caracteres raros) genera un UUID. Lo devuelve en la
 * respuesta y lo deja en el MDC como {@value #MDC_KEY}, para que cada línea del log de esa
 * solicitud (en el formato JSON, un campo) se pueda unir con las demás.
 *
 * <p>El valor recibido se valida antes de usarlo: un id con saltos de línea o texto arbitrario
 * permitiría falsificar líneas del log. No es un dato del estudiante ni un secreto; no
 * identifica a nadie.
 *
 * <p>Va primero en la cadena de filtros, antes de la autenticación, para que también los 401
 * lleven id.
 */
public final class RequestIdFilter extends OncePerRequestFilter implements Ordered {

    public static final String HEADER = "X-Request-Id";
    public static final String MDC_KEY = "request_id";

    private static final Pattern SAFE = Pattern.compile("[A-Za-z0-9._-]{1,64}");

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String id = request.getHeader(HEADER);
        if (id == null || !SAFE.matcher(id).matches()) {
            id = UUID.randomUUID().toString();
        }
        response.setHeader(HEADER, id);
        MDC.put(MDC_KEY, id);
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_KEY);
        }
    }
}
