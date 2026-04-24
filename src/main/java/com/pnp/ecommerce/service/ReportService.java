package com.pnp.ecommerce.service;

import com.pnp.ecommerce.dto.TopProductsReport;
import com.pnp.ecommerce.enumtype.OrderStatus;

import java.time.Instant;
import java.util.Set;

/**
 * Contract for reporting queries. Separate interface — same DIP
 * rationale as {@link ProductService} / {@link OrderService}.
 *
 * <p>Why the statuses filter accepts a {@code Set} (not a single
 * value): the {@code status} query parameter is repeatable per
 * {@code docs/API-DESIGN.md} §3.7 — a caller may want cancelled
 * orders included for a demand-signal view. The service defaults to
 * {@code CONFIRMED}-only when the caller omits the parameter.
 */
public interface ReportService {

    TopProductsReport getTopProducts(Instant fromInclusive,
                                     Instant toExclusive,
                                     Set<OrderStatus> statuses,
                                     int limit);
}
