package com.pnp.ecommerce.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * Response shape for {@code GET /api/v1/reports/top-products}. The
 * envelope echoes the caller's inputs ({@code from}, {@code to},
 * {@code limit}) so a stored payload is self-describing without the
 * original request URL.
 *
 * <p>Why a nested {@code Row} record (not a top-level DTO): the row
 * shape has no meaning outside this report. Keeping it nested matches
 * the cohesion rule used in {@link OrderResponse} and makes
 * {@code TopProductsReport.Row} its fully-qualified handle.
 *
 * <p>Why {@code Long} (not {@code int}) for {@code totalQuantity} and
 * {@code orderCount}: Postgres returns {@code SUM(INTEGER)} and
 * {@code COUNT(*)} as {@code BIGINT} — the JDBC driver maps those to
 * {@code Long}. Using {@code Long} removes an implicit narrowing and
 * matches the result set exactly.
 *
 * <p>Why {@code BigDecimal} for {@code totalRevenue}: preserves money
 * precision end-to-end ({@code NUMERIC(12,2)} → {@code BigDecimal} →
 * JSON number with 2 decimals). Floats would silently drop cents.
 */
public record TopProductsReport(
        Instant from,
        Instant to,
        int limit,
        List<Row> results
) {

    public record Row(
            Long productId,
            String productName,
            Long totalQuantity,
            BigDecimal totalRevenue,
            Long orderCount
    ) {
    }
}
