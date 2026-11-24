package sched.adapters.http;

import java.io.IOException;

/** El cuerpo leído superó el máximo; la lanza el flujo de {@link BodySizeLimitFilter} cuando no hay {@code Content-Length}. */
final class BodyTooLargeException extends IOException {

    private final long maxBytes;

    BodyTooLargeException(long maxBytes) {
        super(HttpErrors.payloadTooLargeMessage(maxBytes));
        this.maxBytes = maxBytes;
    }

    long maxBytes() {
        return maxBytes;
    }
}
