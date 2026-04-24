package com.pnp.ecommerce.repository;

import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * JDBC-backed reporting repository (Phase 4 skeleton; Phase 8 fills in
 * the SQL).
 *
 * <p>Why a concrete {@code @Repository} class instead of a Spring Data
 * interface: the queries here are aggregations over joins with no
 * managed entity graph to return. JdbcTemplate gives us precise
 * control over SQL shape, projection class, and parameter binding —
 * exactly what reporting needs and what JPA hides.
 *
 * <p>Why no separate interface (unlike services): repositories aren't
 * substituted at runtime in this codebase, and Spring Data repositories
 * themselves are interfaces. Adding an interface here just to mirror
 * the service pattern would be ceremony without benefit. If a second
 * implementation is ever needed (e.g. a cached one), an interface can
 * be extracted then.
 *
 * <p><b>Query classification (when methods are added in Phase 8):</b>
 * SQL-driven via {@link NamedParameterJdbcTemplate}. JPQL would express
 * the query, but native SQL keeps the door open for window functions /
 * CTEs and avoids any entity-hydration overhead for read-only reports.
 */
@Repository
public class ReportJdbcRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public ReportJdbcRepository(final NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Visible to Phase 8 implementations; package-private accessor avoids
     * a {@code @SuppressWarnings("unused")} on the field while we wait
     * for the report method to land.
     */
    NamedParameterJdbcTemplate jdbc() {
        return jdbc;
    }
}
