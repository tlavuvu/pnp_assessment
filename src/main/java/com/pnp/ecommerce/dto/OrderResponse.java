package com.pnp.ecommerce.dto;

import com.pnp.ecommerce.enumtype.OrderStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * Order detail payload, used by {@code POST /orders} (201 body) and
 * {@code GET /orders/&#123;id&#125;}.
 *
 * <p>Why a nested {@code Item} response (vs. a reused top-level DTO):
 * same reasoning as {@link CreateOrderRequest.Item} — an order item
 * response only ever appears inside an order response. Nesting keeps
 * the related types one scroll apart and prevents accidental reuse in
 * contexts where {@code productName} is not applicable.
 *
 * <p>{@code unitPrice} and {@code lineTotal} are the snapshots stored
 * on {@code order_item}, not a live lookup from {@code product} — this
 * is the whole point of snapshot pricing (ADR-014, DB-DESIGN.md §2.3).
 */
public record OrderResponse(
        Long id,
        String customerReference,
        OrderStatus status,
        BigDecimal total,
        List<Item> items,
        Instant createdAt,
        Instant updatedAt
) {

    public record Item(
            Long productId,
            String productName,
            Integer quantity,
            BigDecimal unitPrice,
            BigDecimal lineTotal
    ) {
    }
}
