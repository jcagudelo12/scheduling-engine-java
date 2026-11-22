package sched.adapters.http;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Limita el tamaño del cuerpo HTTP: Tomcat no lo hace para JSON. Si {@code Content-Length} ya
 * supera el máximo, responde {@code 413} sin leer nada. Si no hay {@code Content-Length}
 * (transferencia por trozos), cuenta los bytes al leerlos y corta al pasarse con una
 * {@link BodyTooLargeException}, que {@link HttpExceptionHandler} convierte en {@code 413}.
 */
public final class BodySizeLimitFilter extends OncePerRequestFilter {

    private final long maxBytes;

    public BodySizeLimitFilter(long maxBytes) {
        if (maxBytes < 1) {
            throw new IllegalArgumentException("El tamaño máximo del cuerpo debe ser positivo: " + maxBytes);
        }
        this.maxBytes = maxBytes;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (request.getContentLengthLong() > maxBytes) {
            response.setStatus(413);
            response.setContentType("application/json");
            response.setCharacterEncoding("UTF-8");
            response.getWriter().write("{\"error\":\"payload_too_large\",\"message\":\""
                    + HttpErrors.payloadTooLargeMessage(maxBytes) + "\"}");
            return;
        }
        chain.doFilter(new Limited(request, maxBytes), response);
    }

    private static final class Limited extends HttpServletRequestWrapper {
        private final long maxBytes;

        Limited(HttpServletRequest request, long maxBytes) {
            super(request);
            this.maxBytes = maxBytes;
        }

        @Override
        public ServletInputStream getInputStream() throws IOException {
            return new Counting(super.getInputStream(), maxBytes);
        }

        @Override
        public BufferedReader getReader() throws IOException {
            String name = getCharacterEncoding();
            Charset charset = name == null ? StandardCharsets.UTF_8 : Charset.forName(name);
            return new BufferedReader(new InputStreamReader(getInputStream(), charset));
        }
    }

    private static final class Counting extends ServletInputStream {
        private final ServletInputStream in;
        private final long maxBytes;
        private long count;

        Counting(ServletInputStream in, long maxBytes) {
            this.in = in;
            this.maxBytes = maxBytes;
        }

        @Override
        public int read() throws IOException {
            int b = in.read();
            if (b >= 0) {
                add(1);
            }
            return b;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            int n = in.read(buffer, offset, length);
            if (n > 0) {
                add(n);
            }
            return n;
        }

        private void add(int n) throws IOException {
            count += n;
            if (count > maxBytes) {
                throw new BodyTooLargeException(maxBytes);
            }
        }

        @Override
        public boolean isFinished() {
            return in.isFinished();
        }

        @Override
        public boolean isReady() {
            return in.isReady();
        }

        @Override
        public void setReadListener(ReadListener listener) {
            in.setReadListener(listener);
        }
    }
}
