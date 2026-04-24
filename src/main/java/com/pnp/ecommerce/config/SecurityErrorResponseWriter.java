package com.pnp.ecommerce.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pnp.ecommerce.dto.ErrorResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.util.List;

/**
 * Writes a JSON {@link ErrorResponse} envelope directly to a servlet
 * response. Used by three sites that cannot return a value through
 * {@code @RestControllerAdvice}:
 *
 * <ul>
 *   <li>{@code AuthenticationEntryPoint} — 401 before any controller
 *       sees the request.</li>
 *   <li>{@code AccessDeniedHandler} — 403 after auth succeeds but
 *       authorization fails.</li>
 *   <li>{@code RateLimitFilter} — 429 when a bucket is exhausted.</li>
 * </ul>
 *
 * <p>Why a shared component (vs. three copies of the same 5 lines):
 * the envelope shape is a contract. Three copies drift. One shared
 * writer stays in step with {@code GlobalExceptionHandler} by
 * construction.
 */
@Component
public class SecurityErrorResponseWriter {

    private final ObjectMapper objectMapper;
    private final Clock clock;

    public SecurityErrorResponseWriter(final ObjectMapper objectMapper, final Clock clock) {
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    public void write(final HttpServletResponse response,
                      final HttpServletRequest request,
                      final HttpStatus status,
                      final String code,
                      final String message) throws IOException {
        if (response.isCommitted()) {
            return;
        }
        final ErrorResponse body = new ErrorResponse(
                Instant.now(clock),
                status.value(),
                status.getReasonPhrase(),
                code,
                message,
                request.getRequestURI(),
                List.of()
        );
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        objectMapper.writeValue(response.getOutputStream(), body);
    }
}
