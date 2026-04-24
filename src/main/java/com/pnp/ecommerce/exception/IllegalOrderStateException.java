package com.pnp.ecommerce.exception;

import com.pnp.ecommerce.enumtype.OrderStatus;

/**
 * Raised when an operation is attempted on an order whose current
 * status forbids the transition — at present, trying to cancel an
 * already-cancelled order. Maps to HTTP 409 / code {@value #CODE}
 * in the Phase 7 handler.
 *
 * <p>Kept deliberately generic (not {@code OrderNotCancellableException})
 * so future illegal transitions (e.g. cancelling a SHIPPED order, if
 * that state is ever added) can reuse the class with a different code.
 * For now, {@code ORDER_NOT_CANCELLABLE} is the only emitted code.
 */
public final class IllegalOrderStateException extends DomainException {

    public static final String CODE = "ORDER_NOT_CANCELLABLE";

    private final long orderId;
    private final OrderStatus currentStatus;

    public IllegalOrderStateException(final long orderId, final OrderStatus currentStatus) {
        super(CODE, "order " + orderId + " cannot be cancelled from status " + currentStatus);
        this.orderId = orderId;
        this.currentStatus = currentStatus;
    }

    public long getOrderId() {
        return orderId;
    }

    public OrderStatus getCurrentStatus() {
        return currentStatus;
    }
}
