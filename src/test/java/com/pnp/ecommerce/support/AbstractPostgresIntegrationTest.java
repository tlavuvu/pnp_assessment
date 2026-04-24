package com.pnp.ecommerce.support;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * Base class for every HTTP-driven integration test. Boots the full
 * Spring context against a Testcontainers-managed Postgres via the
 * {@code jdbc:tc:postgresql:...} URL declared in
 * {@code application-test.yml}.
 *
 * <p>Why MockMvc (vs. {@code @SpringBootTest(webEnvironment=RANDOM_PORT)}
 * + {@code TestRestTemplate}): MockMvc runs the real servlet pipeline
 * including the security filter chain without a real port, which is
 * both faster and safer for CI (no port contention). The concurrency
 * test is the only case where this trade-off flips, and it bypasses
 * MockMvc by calling the service bean directly.
 *
 * <p>Why {@code @SpringBootTest(classes = ...)} is not used: the
 * full context is what we want to exercise — controllers, security,
 * rate limiter, exception handler, repositories. Slicing would hide
 * wiring bugs.
 *
 * <p>Per-test cleanup deletes every order + order-item and every
 * product created <em>after</em> the 10 Liquibase-seeded rows
 * (ids 1..10). This keeps seed-dependent tests stable while letting
 * write tests inject their own products without polluting each other.
 */
@SpringBootTest
@ActiveProfiles("test")
public abstract class AbstractPostgresIntegrationTest {

    protected static final int SEED_PRODUCT_COUNT = 10;

    @Autowired
    protected WebApplicationContext webApplicationContext;

    @Autowired
    protected ObjectMapper objectMapper;

    @Autowired
    protected JdbcTemplate jdbcTemplate;

    protected MockMvc mockMvc;

    @BeforeEach
    void setUpMockMvc() {
        this.mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
                .apply(org.springframework.security.test.web.servlet.setup
                        .SecurityMockMvcConfigurers.springSecurity())
                .build();
    }

    @BeforeEach
    void cleanDatabaseState() {
        jdbcTemplate.update("DELETE FROM order_item");
        jdbcTemplate.update("DELETE FROM orders");
        jdbcTemplate.update("DELETE FROM product WHERE id > ?", SEED_PRODUCT_COUNT);
    }

    protected static RequestPostProcessor asAdmin() {
        return SecurityMockMvcRequestPostProcessors.httpBasic("admin", "admin123");
    }

    protected static RequestPostProcessor asUser() {
        return SecurityMockMvcRequestPostProcessors.httpBasic("user", "user123");
    }
}
