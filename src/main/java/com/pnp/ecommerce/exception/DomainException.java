package com.pnp.ecommerce.exception;

import java.util.Objects;

/**
 * Root of the application's business-error hierarchy.
 *
 * <p>Why {@code sealed}: the permitted-subclass list is the
 * authoritative catalogue of domain errors the service layer can
 * throw. A new kind of domain error cannot be introduced without
 * editing this one file, and the Phase 7 global handler can rely on
 * the closed set when mapping errors to HTTP responses.
 *
 * <p>Why extend {@link RuntimeException} (not a checked exception):
 * checked exceptions force intermediate layers (controllers, test
 * scaffolding) into boilerplate {@code throws} clauses for errors they
 * cannot recover from. Unchecked + a global handler is the
 * idiomatic Spring Boot shape.
 *
 * <p>Every domain error carries a stable machine-readable {@code code}
 * that matches {@code docs/API-DESIGN.md} §2. The human-readable
 * {@code message} is the default — localisation / copy edits must
 * never break API clients.
 */
public sealed abstract class DomainException extends RuntimeException
        permits ProductNotFoundException,
                OrderNotFoundException,
                InsufficientStockException,
                IllegalOrderStateException,
                InvalidReportRangeException {

    private final String code;

    protected DomainException(final String code, final String message) {
        super(message);
        this.code = Objects.requireNonNull(code, "code");
    }

    public String getCode() {
        return code;
    }
}
