package com.pnp.ecommerce.exception;

/**
 * Raised when an order id referenced by an API call does not exist.
 * Maps to HTTP 404 / code {@value #CODE} in the Phase 7 handler.
 */
public final class OrderNotFoundException extends DomainException {

    public static final String CODE = "ORDER_NOT_FOUND";

    private final long orderId;

    public OrderNotFoundException(final long orderId) {
        super(CODE, "order with id " + orderId + " was not found");
        this.orderId = orderId;
    }

    public long getOrderId() {
        return orderId;
    }
}
