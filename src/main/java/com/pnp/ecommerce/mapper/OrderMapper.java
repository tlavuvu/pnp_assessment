package com.pnp.ecommerce.mapper;

import com.pnp.ecommerce.dto.OrderResponse;
import com.pnp.ecommerce.entity.Order;
import com.pnp.ecommerce.entity.OrderItem;

import java.util.List;

/**
 * {@link Order} → {@link OrderResponse} translator.
 *
 * <p>No request-side method because order creation is a service-level
 * orchestration (stock locks, price snapshots, total recompute) rather
 * than a pure data transform — see {@code OrderServiceImpl.placeOrder}
 * in Phase 6.
 *
 * <p>Accessing {@code item.getProduct().getName()} triggers a lazy
 * load unless the product was already hydrated. Callers MUST therefore
 * invoke this mapper either:
 *
 * <ul>
 *   <li>inside the service transaction that just loaded the products
 *       (e.g. after {@code findAllByIdForUpdateOrdered}), or</li>
 *   <li>after {@code OrderRepository.findByIdWithItems} which fetches
 *       the product graph with {@code LEFT JOIN FETCH}.</li>
 * </ul>
 *
 * <p>Calling this mapper outside both paths will throw
 * {@link org.hibernate.LazyInitializationException} because
 * {@code open-in-view} is disabled. That's the intended safety net.
 */
public final class OrderMapper {

    private OrderMapper() {
        // utility class
    }

    public static OrderResponse toResponse(final Order order) {
        final List<OrderResponse.Item> items = order.getItems().stream()
                .map(OrderMapper::toItem)
                .toList();

        return new OrderResponse(
                order.getId(),
                order.getCustomerReference(),
                order.getStatus(),
                order.getTotal(),
                items,
                order.getCreatedAt(),
                order.getUpdatedAt()
        );
    }

    private static OrderResponse.Item toItem(final OrderItem item) {
        return new OrderResponse.Item(
                item.getProduct().getId(),
                item.getProduct().getName(),
                item.getQuantity(),
                item.getUnitPrice(),
                item.getLineTotal()
        );
    }
}
