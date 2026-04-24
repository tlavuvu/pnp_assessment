package com.pnp.ecommerce.exception;

import java.time.Instant;

/**
 * Raised when report query parameters fail the service-level range
 * rules — {@code to <= from} or {@code to - from > 366 days}. Bean
 * Validation already catches per-field issues (missing / malformed /
 * out-of-bounds {@code limit}); this class covers the cross-field
 * rules that Bean Validation can't express without a custom
 * constraint class.
 *
 * <p>Maps to HTTP 400 / code {@value #CODE} in the Phase 7 handler.
 */
public final class InvalidReportRangeException extends DomainException {

    public static final String CODE = "INVALID_REPORT_RANGE";

    private final Instant from;
    private final Instant to;

    public InvalidReportRangeException(final String message, final Instant from, final Instant to) {
        super(CODE, message);
        this.from = from;
        this.to = to;
    }

    public Instant getFrom() {
        return from;
    }

    public Instant getTo() {
        return to;
    }
}
