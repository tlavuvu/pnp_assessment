package com.pnp.ecommerce.repository;

import com.pnp.ecommerce.dto.TopProductsReport;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collection;
import java.util.List;

/**
 * JDBC-backed reporting repository. Native SQL is used by design:
 *
 * <ul>
 *   <li><b>Query classification:</b> SQL-driven via
 *       {@link NamedParameterJdbcTemplate}. Chosen over JPQL because
 *       this report aggregates ({@code SUM}, {@code COUNT}) and
 *       orders on <em>derived</em> columns, projects into a dedicated
 *       row type (no managed entity), and will plausibly grow to use
 *       Postgres-specific features (CTEs, window functions) that JPQL
 *       cannot express.</li>
 *   <li><b>Transactional guarantee:</b> caller is responsible for
 *       running this inside a {@code @Transactional(readOnly=true)}
 *       boundary. The method itself issues one SELECT; no locks, no
 *       writes.</li>
 *   <li><b>Parameter binding:</b> {@link MapSqlParameterSource} with
 *       named parameters. Forbids ad-hoc string concatenation, which
 *       is the principal SQL-injection vector for report queries.</li>
 *   <li><b>{@code status} filter:</b> defaults to {@code 'CONFIRMED'}
 *       only (set on the service layer). Cancelled orders do not
 *       contribute to "top-selling" totals unless the caller
 *       explicitly overrides {@code status} on the query string.</li>
 * </ul>
 *
 * <p>The {@code ORDER BY total_quantity DESC, total_revenue DESC,
 * p.id ASC} clause is deliberate: units moved is the primary signal,
 * revenue is a deterministic tiebreaker with business meaning, and
 * {@code p.id ASC} is a final deterministic tiebreaker so the same
 * input always yields the same ordering (otherwise Postgres may
 * permute ties between runs, which breaks tests and flaky dashboards).
 */
@Repository
public class ReportJdbcRepository {

    private static final String SQL_TOP_PRODUCTS = """
            SELECT p.id                        AS product_id,
                   p.name                      AS product_name,
                   SUM(oi.quantity)::BIGINT    AS total_quantity,
                   SUM(oi.line_total)          AS total_revenue,
                   COUNT(DISTINCT o.id)::BIGINT AS order_count
              FROM order_item oi
              JOIN orders     o ON o.id = oi.order_id
              JOIN product    p ON p.id = oi.product_id
             WHERE o.status IN (:statuses)
               AND o.created_at >= :fromTs
               AND o.created_at <  :toTs
             GROUP BY p.id, p.name
             ORDER BY total_quantity DESC, total_revenue DESC, p.id ASC
             LIMIT :limit
            """;

    private static final RowMapper<TopProductsReport.Row> ROW_MAPPER = ReportJdbcRepository::mapRow;

    private final NamedParameterJdbcTemplate jdbc;

    public ReportJdbcRepository(final NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<TopProductsReport.Row> findTopProducts(final Instant fromInclusive,
                                                        final Instant toExclusive,
                                                        final Collection<String> statuses,
                                                        final int limit) {
        final MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("statuses", statuses)
                .addValue("fromTs", Timestamp.from(fromInclusive))
                .addValue("toTs", Timestamp.from(toExclusive))
                .addValue("limit", limit);
        return jdbc.query(SQL_TOP_PRODUCTS, params, ROW_MAPPER);
    }

    private static TopProductsReport.Row mapRow(final ResultSet rs, final int rowNum) throws SQLException {
        return new TopProductsReport.Row(
                rs.getLong("product_id"),
                rs.getString("product_name"),
                rs.getLong("total_quantity"),
                rs.getBigDecimal("total_revenue"),
                rs.getLong("order_count")
        );
    }
}
