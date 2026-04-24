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
import com.pnp.ecommerce.repository.OrderRepository;
import com.pnp.ecommerce.repository.ProductRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link OrderServiceImpl}. Pure Mockito — see the
 * same rationale as {@link ProductServiceImplTest}.
 *
 * <p>The concurrency guarantees documented on {@code placeOrder} and
 * {@code cancel} (pessimistic lock, deadlock-free lock order) are
 * exercised in
 * {@link com.pnp.ecommerce.integration.OrderConcurrencyIntegrationTest}
 * against a real Postgres instance — behaviour that <em>cannot</em>
 * be mocked.
 */
@ExtendWith(MockitoExtension.class)
class OrderServiceImplTest {

    @Mock
    private ProductRepository productRepository;

    @Mock
    private OrderRepository orderRepository;

    @InjectMocks
    private OrderServiceImpl service;

    @Test
    @DisplayName("placeOrder: decrements stock, snapshots price, persists order")
    void placeOrderSuccess() {
        final Product p1 = product(1L, "Widget", new BigDecimal("10.00"), 5);
        final Product p2 = product(2L, "Gadget", new BigDecimal("2.50"), 20);
        when(productRepository.findAllByIdForUpdateOrdered(List.of(1L, 2L)))
                .thenReturn(List.of(p1, p2));
        when(orderRepository.save(any(Order.class))).thenAnswer(inv -> {
            final Order o = inv.getArgument(0);
            ReflectionTestUtils.setField(o, "id", 100L);
            ReflectionTestUtils.setField(o, "createdAt", Instant.now());
            ReflectionTestUtils.setField(o, "updatedAt", Instant.now());
            return o;
        });

        final CreateOrderRequest request = new CreateOrderRequest(
                "cust-1",
                List.of(new CreateOrderRequest.Item(1L, 2),
                        new CreateOrderRequest.Item(2L, 3)));

        final OrderResponse response = service.placeOrder(request);

        assertThat(p1.getStock()).isEqualTo(3);
        assertThat(p2.getStock()).isEqualTo(17);
        assertThat(response.id()).isEqualTo(100L);
        assertThat(response.status()).isEqualTo(OrderStatus.CONFIRMED);
        assertThat(response.total()).isEqualByComparingTo("27.50");
        assertThat(response.items()).hasSize(2);
        assertThat(response.items().get(0).unitPrice()).isEqualByComparingTo("10.00");
    }

    @Test
    @DisplayName("placeOrder: locks product rows in ASCENDING id order (deadlock safety)")
    void placeOrderLocksInAscendingIdOrder() {
        final Product p10 = product(10L, "A", new BigDecimal("1.00"), 10);
        final Product p7 = product(7L, "B", new BigDecimal("1.00"), 10);
        when(productRepository.findAllByIdForUpdateOrdered(anyList()))
                .thenReturn(List.of(p7, p10));
        when(orderRepository.save(any(Order.class))).thenAnswer(inv -> inv.getArgument(0));

        final CreateOrderRequest request = new CreateOrderRequest(
                "cust-1",
                List.of(new CreateOrderRequest.Item(10L, 1),
                        new CreateOrderRequest.Item(7L, 1)));

        service.placeOrder(request);

        @SuppressWarnings("unchecked")
        final ArgumentCaptor<List<Long>> captor = ArgumentCaptor.forClass(List.class);
        verify(productRepository).findAllByIdForUpdateOrdered(captor.capture());
        assertThat(captor.getValue()).containsExactly(7L, 10L);
    }

    @Test
    @DisplayName("placeOrder: missing product produces ProductNotFoundException")
    void placeOrderUnknownProduct() {
        when(productRepository.findAllByIdForUpdateOrdered(List.of(99L))).thenReturn(List.of());

        final CreateOrderRequest request = new CreateOrderRequest(
                "cust-1", List.of(new CreateOrderRequest.Item(99L, 1)));

        assertThatThrownBy(() -> service.placeOrder(request))
                .isInstanceOf(ProductNotFoundException.class)
                .hasMessageContaining("99");
        verifyNoInteractions(orderRepository);
    }

    @Test
    @DisplayName("placeOrder: over-ordering produces InsufficientStockException, no save")
    void placeOrderInsufficientStock() {
        final Product p = product(1L, "Widget", new BigDecimal("10.00"), 1);
        when(productRepository.findAllByIdForUpdateOrdered(List.of(1L))).thenReturn(List.of(p));

        final CreateOrderRequest request = new CreateOrderRequest(
                "cust-1", List.of(new CreateOrderRequest.Item(1L, 5)));

        assertThatThrownBy(() -> service.placeOrder(request))
                .isInstanceOf(InsufficientStockException.class);
        assertThat(p.getStock()).isEqualTo(1);
        verifyNoInteractions(orderRepository);
    }

    @Test
    @DisplayName("getById: missing order produces OrderNotFoundException")
    void getByIdMissingThrows() {
        when(orderRepository.findByIdWithItems(77L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getById(77L))
                .isInstanceOf(OrderNotFoundException.class)
                .hasMessageContaining("77");
    }

    @Test
    @DisplayName("cancel: restores stock and flips status to CANCELLED")
    void cancelRestoresStock() {
        final Product p = product(1L, "Widget", new BigDecimal("10.00"), 3);
        final Order order = new Order("cust-1", OrderStatus.CONFIRMED);
        order.addItem(new OrderItem(p, 2, new BigDecimal("10.00")));
        order.recalculateTotal();
        ReflectionTestUtils.setField(order, "id", 100L);
        ReflectionTestUtils.setField(order, "createdAt", Instant.now());
        ReflectionTestUtils.setField(order, "updatedAt", Instant.now());

        when(orderRepository.findByIdWithItems(100L)).thenReturn(Optional.of(order));
        when(productRepository.findAllByIdForUpdateOrdered(List.of(1L))).thenReturn(List.of(p));

        final OrderResponse response = service.cancel(100L);

        assertThat(response.status()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(p.getStock()).isEqualTo(5);
    }

    @Test
    @DisplayName("cancel: already-cancelled order is NOT idempotent — throws 409")
    void cancelIsNotIdempotent() {
        final Product p = product(1L, "Widget", new BigDecimal("10.00"), 5);
        final Order order = new Order("cust-1", OrderStatus.CANCELLED);
        order.addItem(new OrderItem(p, 1, new BigDecimal("10.00")));
        ReflectionTestUtils.setField(order, "id", 100L);
        ReflectionTestUtils.setField(order, "createdAt", Instant.now());
        ReflectionTestUtils.setField(order, "updatedAt", Instant.now());

        when(orderRepository.findByIdWithItems(100L)).thenReturn(Optional.of(order));

        assertThatThrownBy(() -> service.cancel(100L))
                .isInstanceOf(IllegalOrderStateException.class);
        assertThat(p.getStock()).isEqualTo(5);
        verify(productRepository, org.mockito.Mockito.never())
                .findAllByIdForUpdateOrdered(anyList());
    }

    private static Product product(final Long id,
                                   final String name,
                                   final BigDecimal price,
                                   final int stock) {
        final Product p = new Product(name, null, price, stock);
        ReflectionTestUtils.setField(p, "id", id);
        ReflectionTestUtils.setField(p, "createdAt", Instant.now());
        ReflectionTestUtils.setField(p, "updatedAt", Instant.now());
        return p;
    }

    private static <T> T any(final Class<T> type) {
        return org.mockito.ArgumentMatchers.any(type);
    }
}
