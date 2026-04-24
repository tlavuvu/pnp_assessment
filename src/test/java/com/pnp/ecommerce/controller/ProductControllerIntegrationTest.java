package com.pnp.ecommerce.controller;

import com.pnp.ecommerce.dto.ProductRequest;
import com.pnp.ecommerce.support.AbstractPostgresIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.matchesPattern;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests for {@code /api/v1/products}. Covers:
 * <ul>
 *   <li>201 with Location header on admin create</li>
 *   <li>200 on get by id</li>
 *   <li>200 paginated listing (seeded products)</li>
 *   <li>400 body envelope on validation failure</li>
 *   <li>404 body envelope on unknown id</li>
 * </ul>
 */
class ProductControllerIntegrationTest extends AbstractPostgresIntegrationTest {

    @Test
    @DisplayName("POST /api/v1/products (ADMIN): 201 + Location + body")
    void createProductAsAdmin() throws Exception {
        final ProductRequest request = new ProductRequest(
                "Test-Widget", "A test widget", new BigDecimal("19.99"), 25);

        mockMvc.perform(post("/api/v1/products")
                        .with(asAdmin())
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", matchesPattern(".*/api/v1/products/\\d+")))
                .andExpect(jsonPath("$.id").value(greaterThan(SEED_PRODUCT_COUNT)))
                .andExpect(jsonPath("$.name").value("Test-Widget"))
                .andExpect(jsonPath("$.stock").value(25))
                .andExpect(jsonPath("$.price").value(19.99));
    }

    @Test
    @DisplayName("POST /api/v1/products: 400 VALIDATION_FAILED on bad body")
    void createProductBadBody() throws Exception {
        final String body = """
                {"name":"","price":-1,"stock":-5}
                """;

        mockMvc.perform(post("/api/v1/products")
                        .with(asAdmin())
                        .contentType("application/json")
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors").isArray());
    }

    @Test
    @DisplayName("GET /api/v1/products/{id}: returns seeded product 1")
    void getSeededProduct() throws Exception {
        mockMvc.perform(get("/api/v1/products/1").with(asUser()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.name").exists())
                .andExpect(jsonPath("$.price").exists())
                .andExpect(jsonPath("$.stock").exists());
    }

    @Test
    @DisplayName("GET /api/v1/products/{id}: 404 PRODUCT_NOT_FOUND on missing id")
    void getUnknownProduct() throws Exception {
        mockMvc.perform(get("/api/v1/products/9999999").with(asUser()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PRODUCT_NOT_FOUND"))
                .andExpect(jsonPath("$.status").value(404));
    }

    @Test
    @DisplayName("GET /api/v1/products: paginates seed data")
    void listSeededProducts() throws Exception {
        mockMvc.perform(get("/api/v1/products")
                        .param("page", "0")
                        .param("size", "5")
                        .with(asUser()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(5))
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(5))
                .andExpect(jsonPath("$.totalElements").value(SEED_PRODUCT_COUNT));
    }

    @Test
    @DisplayName("POST /api/v1/products: body wrapped in canonical error envelope")
    void bodyWrappedInEnvelope() throws Exception {
        mockMvc.perform(post("/api/v1/products")
                        .with(asAdmin())
                        .contentType("application/json")
                        .content("{"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith("application/json"))
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"))
                .andExpect(jsonPath("$.timestamp").exists())
                .andExpect(jsonPath("$.path").value("/api/v1/products"));
    }
}
