package com.pnp.ecommerce.dto;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Implementation for {@link UniqueProductIds}.
 *
 * <p>Null/empty lists return {@code true} because presence + size are
 * already enforced by {@code @NotEmpty @Size(max=50)} on the same
 * field — each constraint should have one responsibility.
 */
public class UniqueProductIdsValidator
        implements ConstraintValidator<UniqueProductIds, List<CreateOrderRequest.Item>> {

    @Override
    public boolean isValid(final List<CreateOrderRequest.Item> items,
                           final ConstraintValidatorContext context) {
        if (items == null || items.isEmpty()) {
            return true;
        }
        final Set<Long> seen = new HashSet<>(items.size() * 2);
        for (final CreateOrderRequest.Item item : items) {
            if (item == null || item.productId() == null) {
                continue;
            }
            if (!seen.add(item.productId())) {
                return false;
            }
        }
        return Objects.equals(seen.size(), countNonNullIds(items));
    }

    private static int countNonNullIds(final List<CreateOrderRequest.Item> items) {
        int count = 0;
        for (final CreateOrderRequest.Item item : items) {
            if (item != null && item.productId() != null) {
                count++;
            }
        }
        return count;
    }
}
