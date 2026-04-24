package com.pnp.ecommerce.integration;

import com.pnp.ecommerce.dto.CreateOrderRequest;
import com.pnp.ecommerce.exception.InsufficientStockException;
import com.pnp.ecommerce.service.OrderService;
import com.pnp.ecommerce.support.AbstractPostgresIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The highest-signal test in the suite. Proves that the pessimistic
 * lock + lock-order discipline declared in
 * {@link com.pnp.ecommerce.service.impl.OrderServiceImpl} actually
 * prevents overselling when concurrent placements race for the same
 * product.
 *
 * <p>Why it goes straight at the {@link OrderService} bean (not the
 * HTTP layer): MockMvc serialises requests through one thread, which
 * would make the test a tautology. The service bean is the real
 * {@code @Transactional} boundary, so invoking it from N threads in
 * parallel exercises the same concurrency path a real load would.
 *
 * <p>The test does NOT share the per-test cleanup behaviour of its
 * parent — it creates one product per run (unique name) and asserts
 * against that row's final state, so parallel execution of other
 * integration tests cannot interfere.
 */
class OrderConcurrencyIntegrationTest extends AbstractPostgresIntegrationTest {

    private static final int INITIAL_STOCK = 20;
    private static final int CONCURRENT_REQUESTS = 50;
    private static final int THREAD_POOL_SIZE = 16;

    @Autowired
    private OrderService orderService;

    @Test
    @DisplayName("50 concurrent placements on stock=20 produce exactly 20 successes, 30 insufficient-stock, stock=0")
    void noOversellUnderContention() throws Exception {
        final long productId = insertProduct(
                "Concurrency-Target-" + System.nanoTime(),
                "1.00",
                INITIAL_STOCK);

        final CountDownLatch startGate = new CountDownLatch(1);
        final CountDownLatch doneGate = new CountDownLatch(CONCURRENT_REQUESTS);
        final AtomicInteger successes = new AtomicInteger(0);
        final AtomicInteger insufficientStock = new AtomicInteger(0);
        final AtomicInteger unexpected = new AtomicInteger(0);

        final ExecutorService pool = Executors.newFixedThreadPool(THREAD_POOL_SIZE);
        try {
            for (int i = 0; i < CONCURRENT_REQUESTS; i++) {
                final int attempt = i;
                pool.submit(() -> {
                    try {
                        startGate.await();
                        orderService.placeOrder(new CreateOrderRequest(
                                "race-" + attempt,
                                List.of(new CreateOrderRequest.Item(productId, 1))));
                        successes.incrementAndGet();
                    } catch (final InsufficientStockException expected) {
                        insufficientStock.incrementAndGet();
                    } catch (final Exception unexpectedEx) {
                        unexpected.incrementAndGet();
                    } finally {
                        doneGate.countDown();
                    }
                });
            }
            startGate.countDown();
            final boolean finishedInTime = doneGate.await(30, TimeUnit.SECONDS);
            assertThat(finishedInTime).as("race finished within 30s").isTrue();
        } finally {
            pool.shutdownNow();
        }

        assertThat(unexpected.get()).as("no unexpected exceptions").isZero();
        assertThat(successes.get()).as("exactly initial-stock orders succeeded").isEqualTo(INITIAL_STOCK);
        assertThat(insufficientStock.get())
                .as("the rest failed with insufficient stock")
                .isEqualTo(CONCURRENT_REQUESTS - INITIAL_STOCK);
        assertThat(stockOf(productId)).as("final stock is zero — no oversell").isZero();
        assertThat(confirmedOrderCountFor(productId))
                .as("exactly initial-stock CONFIRMED orders persisted")
                .isEqualTo(INITIAL_STOCK);
    }

    private long insertProduct(final String name, final String price, final int stock) {
        jdbcTemplate.update(
                "INSERT INTO product(name, price, stock, created_at, updated_at) "
                        + "VALUES (?, ?, ?, NOW(), NOW())",
                name, new BigDecimal(price), stock);
        final Long id = jdbcTemplate.queryForObject(
                "SELECT id FROM product WHERE name = ?", Long.class, name);
        assertThat(id).isNotNull();
        return id;
    }

    private int stockOf(final long productId) {
        final Integer stock = jdbcTemplate.queryForObject(
                "SELECT stock FROM product WHERE id = ?", Integer.class, productId);
        assertThat(stock).isNotNull();
        return stock;
    }

    private int confirmedOrderCountFor(final long productId) {
        final Integer count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)::int
                  FROM orders o
                  JOIN order_item oi ON oi.order_id = o.id
                 WHERE oi.product_id = ?
                   AND o.status = 'CONFIRMED'
                """, Integer.class, productId);
        assertThat(count).isNotNull();
        return count;
    }
}
