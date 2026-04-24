package com.pnp.ecommerce.exception;

/**
 * Stable, machine-readable error codes for the {@code code} field of
 * {@link com.pnp.ecommerce.dto.ErrorResponse}. Domain-exception codes
 * live on the exceptions themselves (e.g.
 * {@link ProductNotFoundException#CODE}); this class hosts only the
 * cross-cutting codes emitted by {@link GlobalExceptionHandler} for
 * framework-level failures.
 *
 * <p>Why a dedicated class (vs. string literals scattered in the
 * handler): keeps the reviewable catalogue in one file and satisfies
 * the "no magic strings" rule. Extending the set requires touching
 * exactly this file, which is where {@code docs/API-DESIGN.md} §2
 * readers will look first.
 */
public final class ErrorCodes {

    public static final String VALIDATION_FAILED = "VALIDATION_FAILED";
    public static final String MALFORMED_REQUEST = "MALFORMED_REQUEST";
    public static final String METHOD_NOT_ALLOWED = "METHOD_NOT_ALLOWED";
    public static final String NOT_FOUND = "NOT_FOUND";
    public static final String INTERNAL_ERROR = "INTERNAL_ERROR";
    public static final String UNAUTHORIZED = "UNAUTHORIZED";
    public static final String FORBIDDEN = "FORBIDDEN";
    public static final String RATE_LIMITED = "RATE_LIMITED";

    private ErrorCodes() {
        // constants holder
    }
}
