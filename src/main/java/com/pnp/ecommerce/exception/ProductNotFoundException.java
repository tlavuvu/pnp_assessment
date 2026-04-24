package com.pnp.ecommerce.exception;

/**
 * Raised when a product id referenced by an API call does not exist.
 * Maps to HTTP 404 / code {@value #CODE} in the Phase 7 handler.
 */
public final class ProductNotFoundException extends DomainException {

    public static final String CODE = "PRODUCT_NOT_FOUND";

    private final long productId;

    public ProductNotFoundException(final long productId) {
        super(CODE, "product with id " + productId + " was not found");
        this.productId = productId;
    }

    public long getProductId() {
        return productId;
    }
}
