package com.pnp.ecommerce.service.impl;

import com.pnp.ecommerce.dto.CreateOrderRequest;
import com.pnp.ecommerce.dto.OrderResponse;
import com.pnp.ecommerce.entity.Order;
import com.pnp.ecommerce.entity.OrderItem;
import com.pnp.ecommerce.entity.Product;
import com.pnp.ecommerce.enumtype.OrderStatus;
import com.pnp.ecommerce.exception.IllegalOrderStateException;
import com.pnp.ecommerce.exception.InsufficientStockException;
import com.pnp.ecommerce.exception.OrderNotFoundException;
import com.pnp.ecommerce.exception.ProductNotFoundException;
import com.pnp.ecommerce.mapper.OrderMapper;
import com.pnp.ecommerce.repository.OrderRepository;
import com.pnp.ecommerce.repository.ProductRepository;
import com.pnp.ecommerce.service.OrderService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static java.util.function.Function.identity;
import static java.util.stream.Collectors.toMap;
import static java.util.stream.Collectors.toSet;

/**
 * Default {@link OrderService} implementation.
 *
 * <p>Design notes live next to the methods they govern. The one
 * class-level rule is the <b>lock-order discipline</b>: every write
 * path that touches {@code product} rows MUST acquire them via
 * {@link ProductRepository#findAllByIdForUpdateOrdered(java.util.Collection)},
 * which sorts ids ascending before issuing the SELECT ... FOR UPDATE.
 * Violating this invariant reintroduces deadlock risk (ADR-015).
 */
@Service
public class OrderServiceImpl implements OrderService {

    private final ProductRepository productRepository;
    private final OrderRepository orderRepository;

    public OrderServiceImpl(final ProductRepository productRepository,
                            final OrderRepository orderRepository) {
        this.productRepository = productRepository;
        this.orderRepository = orderRepository;
    }

    /**
     * Transactional guarantee:
     * <ul>
     *   <li><b>Propagation:</b> {@code REQUIRED} (default). Joins any
     *       ambient tx or starts a new one.</li>
     *   <li><b>Isolation:</b> {@code READ_COMMITTED} (Postgres default).
     *       Row-level pessimistic locks close the oversell window;
     *       raising to {@code REPEATABLE_READ} would add
     *       serialization-failure retries for no extra safety.</li>
     *   <li><b>Locking:</b> {@code SELECT ... FOR UPDATE} on every
     *       touched product row, acquired in ascending id order.</li>
     *   <li><b>Rollback:</b> any {@link RuntimeException} — including
     *       every domain exception below — triggers rollback.</li>
     * </ul>
     *
     * <p><b>What would go wrong without it:</b> concurrent placements
     * for the same product could both read stock = 1, both decrement
     * to 0, and both commit — classic lost-update oversell.
     *
     * <p><b>Alternatives considered:</b>
     * <ul>
     *   <li>Optimistic locking ({@code @Version}) — would force the
     *       service to own a retry loop; rejected in ADR-015.</li>
     *   <li>Atomic {@code UPDATE product SET stock = stock - ? WHERE
     *       id = ? AND stock &gt;= ?} — works for single-product
     *       orders but is awkward for multi-line orders because the
     *       "all-or-nothing" guarantee spans rows.</li>
     * </ul>
     *
     * <p><b>Query classification:</b>
     * <ul>
     *   <li>Product load: ORM-driven JPQL with pessimistic lock — the
     *       result must be a managed entity so Hibernate flushes the
     *       stock decrement on commit.</li>
     *   <li>Order save: ORM-driven — cascades items + back-reference
     *       via {@code CascadeType.ALL}.</li>
     * </ul>
     */
    @Override
    @Transactional
    public OrderResponse placeOrder(final CreateOrderRequest request) {
        final Map<Long, Product> productsById = lockProductsForItems(request.items());
        final Order order = assembleOrder(request, productsById);
        final Order saved = orderRepository.save(order);
        return OrderMapper.toResponse(saved);
    }

    /**
     * Query classification: ORM-driven JPQL with {@code JOIN FETCH} on
     * items and products — one SQL statement, no N+1, read-only tx so
     * no flush overhead.
     */
    @Override
    @Transactional(readOnly = true)
    public OrderResponse getById(final long id) {
        final Order order = orderRepository.findByIdWithItems(id)
                .orElseThrow(() -> new OrderNotFoundException(id));
        return OrderMapper.toResponse(order);
    }

    /**
     * Transactional guarantee: same locking discipline as
     * {@link #placeOrder(CreateOrderRequest)} — every product whose
     * stock will be restored is locked via
     * {@code findAllByIdForUpdateOrdered} before the increment, so a
     * concurrent placement targeting the same products serialises
     * behind this cancel.
     *
     * <p><b>Non-idempotency is intentional</b> (ADR ADR-013 in
     * {@code docs/DECISIONS.md}): a second cancel of the same order
     * must fail loudly with {@code ORDER_NOT_CANCELLABLE} rather than
     * silently returning 200, because the second call would otherwise
     * double-restore stock. A truly idempotent cancel would need a
     * client-supplied operation id; out of scope for this brief.
     *
     * <p><b>Query classification:</b> ORM-driven load for the order
     * (fetch-join to avoid N+1); ORM-driven pessimistic lock on the
     * touched products (managed entities + dirty-check flush).
     */
    @Override
    @Transactional
    public OrderResponse cancel(final long orderId) {
        final Order order = orderRepository.findByIdWithItems(orderId)
                .orElseThrow(() -> new OrderNotFoundException(orderId));
        if (order.getStatus() == OrderStatus.CANCELLED) {
            throw new IllegalOrderStateException(orderId, order.getStatus());
        }
        restoreStockForOrder(order);
        order.markCancelled();
        return OrderMapper.toResponse(order);
    }

    private Map<Long, Product> lockProductsForItems(final List<CreateOrderRequest.Item> items) {
        final List<Long> ids = items.stream()
                .map(CreateOrderRequest.Item::productId)
                .distinct()
                .sorted()
                .toList();
        final List<Product> products = productRepository.findAllByIdForUpdateOrdered(ids);
        if (products.size() != ids.size()) {
            throw firstMissingAsNotFound(ids, products);
        }
        return products.stream().collect(toMap(Product::getId, identity()));
    }

    private Order assembleOrder(final CreateOrderRequest request,
                                final Map<Long, Product> productsById) {
        final Order order = new Order(request.customerReference(), OrderStatus.CONFIRMED);
        for (final CreateOrderRequest.Item item : request.items()) {
            final Product product = productsById.get(item.productId());
            if (product.getStock() < item.quantity()) {
                throw new InsufficientStockException(
                        product.getId(), item.quantity(), product.getStock());
            }
            product.decrementStock(item.quantity());
            order.addItem(new OrderItem(product, item.quantity(), product.getPrice()));
        }
        order.recalculateTotal();
        return order;
    }

    private void restoreStockForOrder(final Order order) {
        final List<Long> ids = order.getItems().stream()
                .map(item -> item.getProduct().getId())
                .distinct()
                .sorted()
                .toList();
        final Map<Long, Product> byId = productRepository.findAllByIdForUpdateOrdered(ids)
                .stream()
                .collect(toMap(Product::getId, identity()));
        for (final OrderItem item : order.getItems()) {
            byId.get(item.getProduct().getId()).incrementStock(item.getQuantity());
        }
    }

    private static ProductNotFoundException firstMissingAsNotFound(final List<Long> requested,
                                                                    final List<Product> found) {
        final Set<Long> foundIds = found.stream().map(Product::getId).collect(toSet());
        final long missingId = requested.stream()
                .filter(id -> !foundIds.contains(id))
                .findFirst()
                .orElseThrow();
        return new ProductNotFoundException(missingId);
    }
}
