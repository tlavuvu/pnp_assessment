package com.pnp.ecommerce.controller;

import com.pnp.ecommerce.dto.CreateOrderRequest;
import com.pnp.ecommerce.support.AbstractPostgresIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end coverage of the top-products report. Exercises the native
 * SQL path in {@link com.pnp.ecommerce.repository.ReportJdbcRepository}
 * against a real Postgres schema with real aggregation. This is the
 * one place we verify ordering tiebreakers, since ORM-level tests
 * cannot fake the SQL optimiser.
 */
class ReportControllerIntegrationTest extends AbstractPostgresIntegrationTest {

    @Test
    @DisplayName("GET /reports/top-products: ranks by quantity DESC with deterministic tiebreakers")
    void topProductsRanking() throws Exception {
        final long a = insertProduct("Top-A", "10.00", 100);
        final long b = insertProduct("Top-B", "20.00", 100);
        final long c = insertProduct("Top-C", "15.00", 100);

        placeOrder(a, 3);
        placeOrder(b, 5);
        placeOrder(c, 5);
        placeOrder(b, 2);

        final Instant from = Instant.now().minusSeconds(3600);
        final Instant to = Instant.now().plusSeconds(3600);

        mockMvc.perform(get("/api/v1/reports/top-products")
                        .param("from", from.toString())
                        .param("to", to.toString())
                        .param("limit", "10")
                        .with(asAdmin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.limit").value(10))
                .andExpect(jsonPath("$.results[0].productId").value((int) b))
                .andExpect(jsonPath("$.results[0].totalQuantity").value(7))
                .andExpect(jsonPath("$.results[1].productId").value((int) c))
                .andExpect(jsonPath("$.results[1].totalQuantity").value(5))
                .andExpect(jsonPath("$.results[2].productId").value((int) a))
                .andExpect(jsonPath("$.results[2].totalQuantity").value(3));
    }

    @Test
    @DisplayName("GET /reports/top-products: excludes CANCELLED orders by default")
    void excludesCancelledByDefault() throws Exception {
        final long productId = insertProduct("Cancelled-Not-Top", "5.00", 100);
        final long orderId = placeOrder(productId, 10);

        mockMvc.perform(post("/api/v1/orders/" + orderId + "/cancel").with(asAdmin()))
                .andExpect(status().isOk());

        final Instant from = Instant.now().minusSeconds(3600);
        final Instant to = Instant.now().plusSeconds(3600);

        mockMvc.perform(get("/api/v1/reports/top-products")
                        .param("from", from.toString())
                        .param("to", to.toString())
                        .param("limit", "10")
                        .with(asAdmin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.results.length()").value(0));
    }

    @Test
    @DisplayName("GET /reports/top-products: 400 INVALID_REPORT_RANGE when to <= from")
    void invalidRange() throws Exception {
        final Instant from = Instant.parse("2026-01-02T00:00:00Z");
        final Instant to = Instant.parse("2026-01-01T00:00:00Z");

        mockMvc.perform(get("/api/v1/reports/top-products")
                        .param("from", from.toString())
                        .param("to", to.toString())
                        .param("limit", "10")
                        .with(asAdmin()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REPORT_RANGE"));
    }

    @Test
    @DisplayName("GET /reports/top-products: USER role gets 403")
    void userRoleForbidden() throws Exception {
        mockMvc.perform(get("/api/v1/reports/top-products")
                        .param("from", Instant.now().minusSeconds(60).toString())
                        .param("to", Instant.now().plusSeconds(60).toString())
                        .param("limit", "10")
                        .with(asUser()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
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

    private long placeOrder(final long productId, final int quantity) throws Exception {
        final CreateOrderRequest req = new CreateOrderRequest(
                "cust-rpt-" + productId + "-" + quantity + "-" + System.nanoTime(),
                List.of(new CreateOrderRequest.Item(productId, quantity)));

        final String json = mockMvc.perform(post("/api/v1/orders")
                        .with(asUser())
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        return objectMapper.readTree(json).get("id").asLong();
    }
}
