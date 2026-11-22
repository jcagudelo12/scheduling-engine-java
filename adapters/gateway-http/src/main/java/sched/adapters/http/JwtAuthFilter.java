package sched.adapters.http;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Collections;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.web.filter.OncePerRequestFilter;
import sched.adapters.http.CombinationsController.InvalidRequestException;
import sched.adapters.http.auth.InvalidTokenException;
import sched.adapters.http.auth.JwtVerifier;

/**
 * Exige {@code Authorization: Bearer <token>} con un token firmado por la institución (ADR 0004)
 * en todo menos {@code /health} y {@code /ready}. Sin token o con uno inválido responde
 * {@code 401 {"error":"unauthorized"}} con {@code WWW-Authenticate: Bearer}, sin distinguir el
 * motivo ante el cliente. Si es válido deja un {@link VerifiedStudent} como atributo de la solicitud.
 *
 * <p>Nunca registra el token ni su contenido: solo el motivo genérico y el {@code kid}.
 */
public final class JwtAuthFilter extends OncePerRequestFilter implements Ordered {

    private static final Logger log = LoggerFactory.getLogger(JwtAuthFilter.class);
    private static final Pattern BEARER = Pattern.compile("Bearer ([^\\s]+)", Pattern.CASE_INSENSITIVE);

    private final JwtVerifier verifier;
    private final RequestLimits limits;

    public JwtAuthFilter(JwtVerifier verifier, RequestLimits limits) {
        this.verifier = verifier;
        this.limits = limits;
    }

    /** Antes que el resto de los filtros: lo no autenticado no gasta más recursos. */
    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 100;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return "/health".equals(path) || "/ready".equals(path);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        var headers = Collections.list(request.getHeaders("Authorization"));
        if (headers.isEmpty()) {
            log.debug("solicitud sin token");
            reject(response);
            return;
        }
        var matcher = BEARER.matcher(headers.size() == 1 ? headers.getFirst() : "");
        if (!matcher.matches()) {
            log.warn("solicitud rechazada: cabecera Authorization mal formada");
            reject(response);
            return;
        }
        VerifiedStudent student;
        try {
            JwtVerifier.Claims claims = verifier.verify(matcher.group(1));
            student = toStudent(claims);
        } catch (InvalidTokenException e) {
            log.warn("token rechazado: motivo={} kid={}", e.reason().code(), e.kid() == null ? "-" : e.kid());
            reject(response);
            return;
        } catch (InvalidRequestException e) {
            // Claims con ids fuera de las reglas del motor: se trata como token inválido.
            log.warn("token rechazado: motivo=claims_fuera_de_limites");
            reject(response);
            return;
        }
        request.setAttribute(VerifiedStudent.ATTRIBUTE, student);
        chain.doFilter(request, response);
    }

    /** Los claims firmados siguen las mismas reglas de forma y tamaño que el cuerpo. */
    private VerifiedStudent toStudent(JwtVerifier.Claims claims) {
        return new VerifiedStudent(
                RequestValidation.id("sub", claims.subject(), limits),
                RequestValidation.idList("courses", claims.courses(), limits.maxCourses(), limits));
    }

    private static void reject(HttpServletResponse response) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setHeader("WWW-Authenticate", "Bearer");
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write(HttpErrors.UNAUTHORIZED_BODY);
    }
}
