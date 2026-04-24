package com.pnp.ecommerce.dto;

import org.springframework.data.domain.Page;

import java.util.List;

/**
 * Generic page envelope for list endpoints. Matches the shape documented
 * in {@code docs/API-DESIGN.md} §3.2.
 *
 * <p>Why a hand-rolled envelope (vs. returning Spring's {@code Page&lt;T&gt;}):
 * Spring's default {@code Page} JSON representation is verbose, unstable
 * across versions (the "pageable" substructure has changed), and leaks
 * framework naming like {@code numberOfElements}. Our own envelope is
 * four clean fields, future-proof, and doesn't couple clients to
 * spring-data's wire format.
 *
 * <p>The static factory keeps the mapping in one place so every
 * controller gets identical shape with no ceremony.
 */
public record PageResponse<T>(
        List<T> content,
        int page,
        int size,
        long totalElements,
        int totalPages
) {

    public static <T> PageResponse<T> from(final Page<T> page) {
        return new PageResponse<>(
                page.getContent(),
                page.getNumber(),
                page.getSize(),
                page.getTotalElements(),
                page.getTotalPages()
        );
    }
}
