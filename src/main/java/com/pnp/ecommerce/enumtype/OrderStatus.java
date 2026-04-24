package com.pnp.ecommerce.enumtype;

/**
 * Lifecycle states for an {@code Order}.
 *
 * <p>Persisted as {@code VARCHAR(16)} via {@link jakarta.persistence.EnumType#STRING}
 * and additionally guarded at the database by a CHECK constraint that
 * mirrors this set (see {@code 002-create-orders.sql}). Two layers of
 * enforcement so neither a schema-only fix nor an app-only fix can
 * silently introduce an unknown value.
 *
 * <p>Why an enum (not a small lookup table): the values are stable,
 * known at compile time, and used in branch logic. A lookup table would
 * add a join and a runtime indirection for zero benefit at this scope.
 */
public enum OrderStatus {

    PENDING,
    CONFIRMED,
    CANCELLED
}
