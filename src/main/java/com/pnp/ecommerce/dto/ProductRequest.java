package com.pnp.ecommerce.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/**
 * Payload for {@code POST /api/v1/products}.
 *
 * <p>Why a {@code record}: it's an immutable value object with
 * auto-generated accessors, equality, and {@code toString} — exactly what
 * a DTO needs and none of what it doesn't. Spring + Jackson 2.15+ bind
 * records natively via the canonical constructor.
 *
 * <p>Why constraints here (not only on the entity): controllers reject
 * bad input at the HTTP boundary with a 400 before a transaction opens.
 * Relying on DB CHECK constraints alone would leak 500s. Entity-side
 * guards still exist as defence-in-depth (ADR-014 style).
 *
 * <p>API-level {@code price >= 0.01} is intentionally stricter than the
 * DB CHECK {@code price >= 0}: the DB permits a promotional 0.00 row to
 * exist historically (via Liquibase seed / admin migration), but the
 * public create-API refuses to mint one.
 */
public record ProductRequest(
        @NotBlank
        @Size(max = 120)
        String name,

        @Size(max = 500)
        String description,

        @NotNull
        @DecimalMin(value = "0.01", inclusive = true)
        @Digits(integer = 10, fraction = 2)
        BigDecimal price,

        @NotNull
        @Min(0)
        Integer stock
) {
}
