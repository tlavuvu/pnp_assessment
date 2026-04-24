package com.pnp.ecommerce.service.impl;

import com.pnp.ecommerce.dto.TopProductsReport;
import com.pnp.ecommerce.enumtype.OrderStatus;
import com.pnp.ecommerce.exception.InvalidReportRangeException;
import com.pnp.ecommerce.repository.ReportJdbcRepository;
import com.pnp.ecommerce.service.ReportService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * Default {@link ReportService} implementation.
 *
 * <p>Why the SQL lives in a sibling repository (not here): services
 * own business rules (default status, range caps, parameter sanity);
 * repositories own persistence shape (SQL text, row mapping). Mixing
 * them produces 500-line services that are hell to test. Splitting
 * keeps each under 200 lines per the rules.
 *
 * <p>Why {@code @Transactional(readOnly = true)}: signals the driver
 * and Hibernate session that no flush / dirty-check / write-set
 * tracking is required, which is measurably cheaper for a pure
 * aggregate. The report never mutates state.
 *
 * <p>Query classification: <b>SQL-driven</b>. See
 * {@link ReportJdbcRepository} for the query + the full rationale.
 */
@Service
public class ReportServiceImpl implements ReportService {

    private static final Set<OrderStatus> DEFAULT_STATUSES = EnumSet.of(OrderStatus.CONFIRMED);
    private static final Duration MAX_RANGE = Duration.ofDays(366);

    private final ReportJdbcRepository reportJdbcRepository;

    public ReportServiceImpl(final ReportJdbcRepository reportJdbcRepository) {
        this.reportJdbcRepository = reportJdbcRepository;
    }

    @Override
    @Transactional(readOnly = true)
    public TopProductsReport getTopProducts(final Instant fromInclusive,
                                            final Instant toExclusive,
                                            final Set<OrderStatus> statuses,
                                            final int limit) {
        validateRange(fromInclusive, toExclusive);
        final Set<OrderStatus> effective = (statuses == null || statuses.isEmpty())
                ? DEFAULT_STATUSES
                : EnumSet.copyOf(statuses);
        final List<TopProductsReport.Row> rows = reportJdbcRepository.findTopProducts(
                fromInclusive,
                toExclusive,
                effective.stream().map(Enum::name).toList(),
                limit);
        return new TopProductsReport(fromInclusive, toExclusive, limit, rows);
    }

    /**
     * Cross-field rules Bean Validation cannot express without a
     * bespoke constraint: {@code to > from} and range ≤ 366 days. The
     * upper-bound cap is a pragmatic guard against accidental
     * full-table scans — a report spanning decades would be a
     * different endpoint with explicit pagination.
     */
    private static void validateRange(final Instant from, final Instant to) {
        if (from == null || to == null) {
            throw new InvalidReportRangeException(
                    "from and to are required", from, to);
        }
        if (!to.isAfter(from)) {
            throw new InvalidReportRangeException(
                    "'to' must be strictly after 'from'", from, to);
        }
        if (Duration.between(from, to).compareTo(MAX_RANGE) > 0) {
            throw new InvalidReportRangeException(
                    "report range must not exceed " + MAX_RANGE.toDays() + " days", from, to);
        }
    }
}
