package com.pnp.ecommerce.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.List;

/**
 * Canonical error envelope for every non-2xx response. Shape is fixed
 * in {@code docs/API-DESIGN.md} §2 so clients can branch on
 * {@code code} without string-matching {@code message}.
 *
 * <p>Why a stable machine-readable {@code code} alongside the
 * human-readable {@code message}: {@code message} may change for
 * localisation or copy-editing; {@code code} is the contract. Clients
 * that key off messages eventually break when copy changes.
 *
 * <p>{@code @JsonInclude(NON_EMPTY)} on {@code fieldErrors} keeps
 * non-validation error bodies free of a useless empty list while
 * guaranteeing the field is present when validation fails.
 */
public record ErrorResponse(
        Instant timestamp,
        int status,
        String error,
        String code,
        String message,
        String path,
        @JsonInclude(JsonInclude.Include.NON_EMPTY)
        List<FieldError> fieldErrors
) {

    public record FieldError(String field, String message) {
    }
}
