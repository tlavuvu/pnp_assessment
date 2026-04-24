package com.pnp.ecommerce.service;

import com.pnp.ecommerce.dto.CreateOrderRequest;
import com.pnp.ecommerce.dto.OrderResponse;

/**
 * Contract for order placement, lookup, and cancellation.
 *
 * <p>Stock-changing methods ({@link #placeOrder(CreateOrderRequest)} and
 * {@link #cancel(long)}) MUST run inside a transaction that also holds
 * pessimistic write locks on every touched {@code product} row
 * (ARCHITECTURE.md §6, ADR-015). That guarantee is enforced on the
 * implementation, not declared here, because it is an implementation
 * concern (see
 * {@link com.pnp.ecommerce.service.impl.OrderServiceImpl}).
 */
public interface OrderService {

    OrderResponse placeOrder(CreateOrderRequest request);

    OrderResponse getById(long id);

    OrderResponse cancel(long orderId);
}
