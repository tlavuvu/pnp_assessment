package com.pnp.ecommerce.dto;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Rejects a {@code List&lt;CreateOrderRequest.Item&gt;} that contains
 * two or more items with the same {@code productId}.
 *
 * <p>Why a bespoke constraint (vs. deduping silently in the service):
 * duplicates almost always indicate a client-side bug. Silently summing
 * their quantities would mask it; a loud 400 at the boundary surfaces
 * it at the right layer.
 *
 * <p>Why scoped to this one DTO shape (vs. a generic "distinct-by"
 * constraint): Jakarta Bean Validation has no built-in distinct-by
 * rule, and writing a fully generic one requires SpEL or method
 * references. Keeping the validator concrete to
 * {@code CreateOrderRequest.Item} makes it five lines and impossible
 * to misuse.
 */
@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT})
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = UniqueProductIdsValidator.class)
public @interface UniqueProductIds {

    String message() default "items must not contain duplicate productId values";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
