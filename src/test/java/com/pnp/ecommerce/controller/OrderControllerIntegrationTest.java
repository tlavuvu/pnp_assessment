package com.pnp.ecommerce.controller;

import com.pnp.ecommerce.dto.CreateOrderRequest;
import com.pnp.ecommerce.support.AbstractPostgresIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.matchesPattern;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests for {@code /api/v1/orders}. Covers the full
 * place / get / cancel flow against a real Postgres schema + all
 * CHECK constraints. Stock decrement and restoration are asserted by
 * reading the {@code product} row directly through {@link #jdbcTemplate}
 * — bypassing the JPA session so we see exactly what the DB committed.
 */
class OrderControllerIntegrationTest extends AbstractPostgresIntegrationTest {

    @Test
    @DisplayName("POST /orders: 201 + decrements stock + returns snapshot price")
    void placeOrderHappyPath() throws Exception {
        final long productId = insertProduct("Race-Item", "2.50", 10);

        final CreateOrderRequest request = new CreateOrderRequest(
                "cust-001",
                List.of(new CreateOrderRequest.Item(productId, 3)));

        mockMvc.perform(post("/api/v1/orders")
                        .with(asUser())
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", matchesPattern(".*/api/v1/orders/\\d+")))
                .andExpect(jsonPath("$.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.total").value(7.50))
                .andExpect(jsonPath("$.items[0].unitPrice").value(2.50))
                .andExpect(jsonPath("$.items[0].lineTotal").value(7.50))
                .andExpect(jsonPath("$.items[0].quantity").value(3));

        assertThat(stockOf(productId)).isEqualTo(7);
    }

    @Test
    @DisplayName("POST /orders: 409 INSUFFICIENT_STOCK when requested > available")
    void placeOrderInsufficientStock() throws Exception {
        final long productId = insertProduct("Tiny-Stock", "1.00", 2);

        final CreateOrderRequest request = new CreateOrderRequest(
                "cust-002",
                List.of(new CreateOrderRequest.Item(productId, 5)));

        mockMvc.perform(post("/api/v1/orders")
                        .with(asUser())
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INSUFFICIENT_STOCK"));

        assertThat(stockOf(productId)).isEqualTo(2);
    }

    @Test
    @DisplayName("POST /orders: 404 PRODUCT_NOT_FOUND when id unknown")
    void placeOrderUnknownProduct() throws Exception {
        final CreateOrderRequest request = new CreateOrderRequest(
                "cust-003",
                List.of(new CreateOrderRequest.Item(9_999_999L, 1)));

        mockMvc.perform(post("/api/v1/orders")
                        .with(asUser())
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PRODUCT_NOT_FOUND"));
    }

    @Test
    @DisplayName("POST /orders: 400 VALIDATION_FAILED on duplicate product id in items")
    void placeOrderDuplicateProductIds() throws Exception {
        final long productId = insertProduct("Dup-Item", "1.00", 10);

        final String body = """
                {
                  "customerReference": "cust-dup",
                  "items": [
                    {"productId": %d, "quantity": 1},
                    {"productId": %d, "quantity": 1}
                  ]
                }
                """.formatted(productId, productId);

        mockMvc.perform(post("/api/v1/orders")
                        .with(asUser())
                        .contentType("application/json")
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    @DisplayName("GET /orders/{id}: returns the placed order with items")
    void getPlacedOrder() throws Exception {
        final long productId = insertProduct("Get-Me", "5.00", 10);
        final long orderId = placeOrder(productId, 2);

        mockMvc.perform(get("/api/v1/orders/" + orderId).with(asUser()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(orderId))
                .andExpect(jsonPath("$.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.items[0].quantity").value(2))
                .andExpect(jsonPath("$.items[0].unitPrice").value(5.00));
    }

    @Test
    @DisplayName("GET /orders/{id}: 404 ORDER_NOT_FOUND")
    void getMissingOrder() throws Exception {
        mockMvc.perform(get("/api/v1/orders/9999999").with(asUser()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ORDER_NOT_FOUND"));
    }

    @Test
    @DisplayName("POST /orders/{id}/cancel (ADMIN): flips status, restores stock")
    void cancelRestoresStock() throws Exception {
        final long productId = insertProduct("Cancel-Me", "3.00", 10);
        final long orderId = placeOrder(productId, 4);
        assertThat(stockOf(productId)).isEqualTo(6);

        mockMvc.perform(post("/api/v1/orders/" + orderId + "/cancel").with(asAdmin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"))
                .andExpect(jsonPath("$.id").value(orderId));

        assertThat(stockOf(productId)).isEqualTo(10);
    }

    @Test
    @DisplayName("POST /orders/{id}/cancel: 409 ORDER_NOT_CANCELLABLE on second cancel (non-idempotent)")
    void secondCancelIs409() throws Exception {
        final long productId = insertProduct("Double-Cancel", "3.00", 10);
        final long orderId = placeOrder(productId, 1);

        mockMvc.perform(post("/api/v1/orders/" + orderId + "/cancel").with(asAdmin()))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/orders/" + orderId + "/cancel").with(asAdmin()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ORDER_NOT_CANCELLABLE"));

        assertThat(stockOf(productId)).isEqualTo(10);
    }

    @Test
    @DisplayName("POST /orders/{id}/cancel: USER role gets 403")
    void cancelAsUserForbidden() throws Exception {
        final long productId = insertProduct("User-Forbidden", "1.00", 3);
        final long orderId = placeOrder(productId, 1);

        mockMvc.perform(post("/api/v1/orders/" + orderId + "/cancel").with(asUser()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));

        assertThat(stockOf(productId)).isEqualTo(2);
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

    private long placeOrder(final long productId, final int quantity) throws Exception {
        final CreateOrderRequest req = new CreateOrderRequest(
                "cust-setup-" + productId,
                List.of(new CreateOrderRequest.Item(productId, quantity)));

        final String json = mockMvc.perform(post("/api/v1/orders")
                        .with(asUser())
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(greaterThan(0)))
                .andReturn().getResponse().getContentAsString();

        return objectMapper.readTree(json).get("id").asLong();
    }
}
