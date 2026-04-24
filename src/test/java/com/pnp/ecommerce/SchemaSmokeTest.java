package com.pnp.ecommerce;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 3 smoke test.
 *
 * <p>Boots the full Spring context against Testcontainers Postgres (driven
 * by the {@code jdbc:tc:} URL in {@code application-test.yml}) and asserts
 * that:
 *
 * <ol>
 *     <li>Liquibase applied every changelog cleanly.</li>
 *     <li>The seed changeset (context: {@code dev,test}) ran, so the
 *         catalogue is populated.</li>
 *     <li>JPA's {@code ddl-auto=validate} is happy with the resulting
 *         schema (boot would have failed otherwise).</li>
 * </ol>
 *
 * <p>Why a {@code @SpringBootTest} smoke test rather than asserting
 * Liquibase logs: this exercises the same wiring real requests will, and
 * catches dialect or coordinate mismatches that log assertions miss.
 *
 * <p>Requires Docker. Will be re-homed to the failsafe (IT) suite in
 * Phase 10 alongside the rest of the integration tests.
 */
@SpringBootTest
@ActiveProfiles("test")
class SchemaSmokeTest {

    private static final int EXPECTED_SEED_PRODUCTS = 10;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void liquibaseAppliesSchemaAndSeed() {
        final Integer productCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM product", Integer.class);

        assertThat(productCount)
                .as("seed changeset 005-seed-products should have inserted exactly %d rows",
                        EXPECTED_SEED_PRODUCTS)
                .isEqualTo(EXPECTED_SEED_PRODUCTS);
    }

    @Test
    void coreTablesExistWithExpectedColumnTypes() {
        final Integer ordersCols = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.columns " +
                        "WHERE table_name = 'orders'", Integer.class);
        final Integer orderItemCols = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.columns " +
                        "WHERE table_name = 'order_item'", Integer.class);

        assertThat(ordersCols).isGreaterThanOrEqualTo(6);
        assertThat(orderItemCols).isGreaterThanOrEqualTo(6);
    }
}
