package com.pnp.ecommerce.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * Payload for {@code POST /api/v1/orders}.
 *
 * <p>Why a nested {@code Item} record (vs. a separate top-level file):
 * {@code Item} has no meaning outside an order request — its lifetime
 * is bounded by the outer record. Nesting keeps cohesion visible in
 * the file tree without creating an orphan DTO class.
 *
 * <p>Why {@code @NotEmpty} + {@code @Size(max=50)} on the list: the
 * two-annotation combo fails on null, fails on empty, and caps the
 * upper bound — each by the clearest single constraint. The alternative
 * {@code @NotNull @Size(min=1,max=50)} is equivalent but reads less
 * directly (it treats emptiness as a size violation, which is really a
 * "must have at least one" rule).
 *
 * <p>Why {@code @UniqueProductIds}: Bean Validation offers no built-in
 * "elements-distinct-by-property" rule. A custom validator is the
 * smallest correct way to reject a payload that would otherwise pass
 * per-item validation yet contain two lines for product 42.
 */
public record CreateOrderRequest(
        @NotBlank
        @Size(max = 64)
        String customerReference,

        @NotEmpty
        @Size(max = 50)
        @Valid
        @UniqueProductIds
        List<Item> items
) {

    public record Item(
            @NotNull
            @Positive
            Long productId,

            @NotNull
            @Min(1)
            @Max(1000)
            Integer quantity
    ) {
    }
}
