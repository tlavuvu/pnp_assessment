package com.pnp.ecommerce.service.impl;

import com.pnp.ecommerce.dto.TopProductsReport;
import com.pnp.ecommerce.enumtype.OrderStatus;
import com.pnp.ecommerce.exception.InvalidReportRangeException;
import com.pnp.ecommerce.repository.ReportJdbcRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link ReportServiceImpl}. The actual SQL lives in
 * {@link ReportJdbcRepository} and is exercised end-to-end by
 * {@code ReportControllerIntegrationTest}. This class pins the
 * business-rule surface that would be invisible in an e2e test:
 * range validation, default status set, and parameter handoff.
 */
@ExtendWith(MockitoExtension.class)
class ReportServiceImplTest {

    @Mock
    private ReportJdbcRepository reportJdbcRepository;

    @InjectMocks
    private ReportServiceImpl service;

    @Test
    @DisplayName("defaults status to CONFIRMED when caller passes null")
    void defaultsStatusToConfirmed() {
        final Instant from = Instant.parse("2026-01-01T00:00:00Z");
        final Instant to = Instant.parse("2026-02-01T00:00:00Z");
        when(reportJdbcRepository.findTopProducts(eq(from), eq(to), any(), anyInt()))
                .thenReturn(List.of());

        service.getTopProducts(from, to, null, 10);

        @SuppressWarnings("unchecked")
        final ArgumentCaptor<Collection<String>> captor = ArgumentCaptor.forClass(Collection.class);
        verify(reportJdbcRepository).findTopProducts(eq(from), eq(to), captor.capture(), eq(10));
        assertThat(captor.getValue()).containsExactly("CONFIRMED");
    }

    @Test
    @DisplayName("passes through caller-supplied statuses as uppercase enum names")
    void passesThroughStatuses() {
        final Instant from = Instant.parse("2026-01-01T00:00:00Z");
        final Instant to = Instant.parse("2026-01-02T00:00:00Z");
        when(reportJdbcRepository.findTopProducts(any(), any(), any(), anyInt()))
                .thenReturn(List.of());

        service.getTopProducts(from, to,
                EnumSet.of(OrderStatus.CONFIRMED, OrderStatus.CANCELLED), 5);

        @SuppressWarnings("unchecked")
        final ArgumentCaptor<Collection<String>> captor = ArgumentCaptor.forClass(Collection.class);
        verify(reportJdbcRepository).findTopProducts(any(), any(), captor.capture(), eq(5));
        assertThat(captor.getValue()).containsExactlyInAnyOrder("CONFIRMED", "CANCELLED");
    }

    @Test
    @DisplayName("maps rows through without modification, preserving order")
    void mapsRowsThrough() {
        final Instant from = Instant.parse("2026-01-01T00:00:00Z");
        final Instant to = Instant.parse("2026-01-31T00:00:00Z");
        final TopProductsReport.Row row = new TopProductsReport.Row(
                1L, "Widget", 50L, new BigDecimal("500.00"), 12L);
        when(reportJdbcRepository.findTopProducts(any(), any(), any(), anyInt()))
                .thenReturn(List.of(row));

        final TopProductsReport report = service.getTopProducts(from, to, Set.of(), 10);

        assertThat(report.results()).containsExactly(row);
        assertThat(report.from()).isEqualTo(from);
        assertThat(report.to()).isEqualTo(to);
        assertThat(report.limit()).isEqualTo(10);
    }

    @Test
    @DisplayName("rejects to <= from")
    void rejectsReversedRange() {
        final Instant from = Instant.parse("2026-01-02T00:00:00Z");
        final Instant to = Instant.parse("2026-01-01T00:00:00Z");

        assertThatThrownBy(() -> service.getTopProducts(from, to, null, 10))
                .isInstanceOf(InvalidReportRangeException.class)
                .hasMessageContaining("'to' must be strictly after 'from'");
    }

    @Test
    @DisplayName("rejects ranges longer than 366 days")
    void rejectsOverlongRange() {
        final Instant from = Instant.parse("2025-01-01T00:00:00Z");
        final Instant to = from.plus(Duration.ofDays(367));

        assertThatThrownBy(() -> service.getTopProducts(from, to, null, 10))
                .isInstanceOf(InvalidReportRangeException.class)
                .hasMessageContaining("366");
    }

    @Test
    @DisplayName("rejects null from / to")
    void rejectsNullEndpoints() {
        final Instant to = Instant.parse("2026-01-01T00:00:00Z");
        assertThatThrownBy(() -> service.getTopProducts(null, to, null, 10))
                .isInstanceOf(InvalidReportRangeException.class);
    }
}
