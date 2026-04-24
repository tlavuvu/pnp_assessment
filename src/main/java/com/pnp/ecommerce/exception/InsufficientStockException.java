package com.pnp.ecommerce.exception;

/**
 * Raised by {@code OrderService.placeOrder} when at least one requested
 * line exceeds the available stock on a pessimistically locked product
 * row. Maps to HTTP 409 / code {@value #CODE} in the Phase 7 handler.
 *
 * <p>Carries the offending {@code productId}, what the caller requested,
 * and what was actually available so the error envelope can be rich
 * enough for a UI to display a precise message without re-querying.
 */
public final class InsufficientStockException extends DomainException {

    public static final String CODE = "INSUFFICIENT_STOCK";

    private final long productId;
    private final int requested;
    private final int available;

    public InsufficientStockException(final long productId,
                                      final int requested,
                                      final int available) {
        super(CODE, "insufficient stock for product " + productId
                + ": requested " + requested + ", available " + available);
        this.productId = productId;
        this.requested = requested;
        this.available = available;
    }

    public long getProductId() {
        return productId;
    }

    public int getRequested() {
        return requested;
    }

    public int getAvailable() {
        return available;
    }
}
