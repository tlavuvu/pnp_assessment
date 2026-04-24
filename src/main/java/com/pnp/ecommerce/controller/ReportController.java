package com.pnp.ecommerce.controller;

import com.pnp.ecommerce.dto.TopProductsReport;
import com.pnp.ecommerce.enumtype.OrderStatus;
import com.pnp.ecommerce.service.ReportService;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Set;

/**
 * Read-only entry point for the {@code top-products} report.
 *
 * <p>Why {@code @Validated} on the class: enables method-parameter
 * constraint validation ({@code @Min}/{@code @Max} on {@code limit}).
 * Violations surface as {@code ConstraintViolationException} which
 * the global handler maps to 400 / {@code VALIDATION_FAILED}.
 *
 * <p>Why {@code @DateTimeFormat(ISO.DATE_TIME)} on {@code from}/{@code to}:
 * makes ISO-8601 parsing explicit and independent of Jackson's
 * configuration (the request params don't go through Jackson; they
 * go through Spring's {@code ConversionService}).
 *
 * <p>Cross-field rules ({@code to > from}, range ≤ 366 days) are
 * enforced in {@code ReportServiceImpl}, not here — Bean Validation
 * cannot express them on loose query parameters without a bespoke
 * container DTO, which would be pure ceremony for three fields.
 */
@RestController
@RequestMapping(path = "/api/v1/reports")
@Validated
public class ReportController {

    private static final int DEFAULT_LIMIT = 10;

    private final ReportService reportService;

    public ReportController(final ReportService reportService) {
        this.reportService = reportService;
    }

    @GetMapping(path = "/top-products")
    public TopProductsReport topProducts(
            @RequestParam
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
            final Instant from,

            @RequestParam
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
            final Instant to,

            @RequestParam(required = false)
            final Set<OrderStatus> status,

            @RequestParam(defaultValue = "" + DEFAULT_LIMIT)
            @Min(1) @Max(100)
            final int limit) {

        return reportService.getTopProducts(from, to, status, limit);
    }
}
