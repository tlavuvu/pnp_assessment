package com.pnp.ecommerce.dto;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Shape returned by every product-read endpoint and the 201 body of
 * {@code POST /api/v1/products}.
 *
 * <p>Why a separate response record (not just the entity): DTOs
 * decouple the wire contract from the persistence model. Entity
 * refactors (renames, relationship changes, stored fields like
 * {@code updated_at}) never break API consumers, and no lazy
 * associations can accidentally serialize into HTTP responses.
 */
public record ProductResponse(
        Long id,
        String name,
        String description,
        BigDecimal price,
        Integer stock,
        Instant createdAt,
        Instant updatedAt
) {
}
