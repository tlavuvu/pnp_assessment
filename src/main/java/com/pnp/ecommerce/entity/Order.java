package com.pnp.ecommerce.entity;

import com.pnp.ecommerce.enumtype.OrderStatus;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Order aggregate root. Owns its {@link OrderItem} collection
 * (composition: cascade ALL + orphanRemoval). Stock changes happen on
 * the {@link Product} side and are coordinated by the service layer
 * inside a single {@code @Transactional} method (ARCHITECTURE.md §6).
 *
 * <p>Why the table is named {@code orders} (plural): {@code ORDER} is
 * SQL-reserved. The Java class stays singular.
 *
 * <p>Why {@code total} is persisted on the header (vs. recomputed on
 * read): keeps reporting cheap and detail-fetch payloads complete
 * without an extra aggregation. Drift risk documented in ADR-014;
 * mitigated by always mutating items + total inside the same service
 * method.
 */
@Entity
@Table(name = "orders")
public class Order {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "customer_reference", nullable = false, length = 64)
    private String customerReference;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private OrderStatus status;

    @Column(name = "total", nullable = false, precision = 12, scale = 2)
    private BigDecimal total = BigDecimal.ZERO;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /**
     * Composition: items live and die with the order. CascadeType.ALL
     * persists/removes lines transitively; orphanRemoval=true deletes
     * an item row if it's removed from this collection. Lazy fetch is
     * the default for a one-to-many so list endpoints do not eagerly
     * hydrate every line — service code that needs items uses the
     * fetch-join query in OrderRepository.
     */
    @OneToMany(mappedBy = "order",
               cascade = CascadeType.ALL,
               orphanRemoval = true,
               fetch = FetchType.LAZY)
    private List<OrderItem> items = new ArrayList<>();

    /** Required by JPA. Should not be called from application code. */
    protected Order() {
        // intentionally empty
    }

    public Order(final String customerReference, final OrderStatus status) {
        this.customerReference = Objects.requireNonNull(customerReference, "customerReference");
        this.status = Objects.requireNonNull(status, "status");
    }

    /**
     * Add a line to this order and wire the back-reference. Callers must
     * use this method rather than {@code getItems().add(...)} because the
     * returned list view is unmodifiable.
     */
    public void addItem(final OrderItem item) {
        Objects.requireNonNull(item, "item");
        item.setOrder(this);
        this.items.add(item);
    }

    /**
     * Recompute and persist {@code total} from the current items. Called
     * by the service layer after lines are populated and before flush so
     * that the DB-side {@code orders.total} stays consistent with
     * {@code SUM(order_item.line_total)}. Defensive against rounding by
     * delegating to {@link BigDecimal#add(BigDecimal)} (no doubles).
     */
    public void recalculateTotal() {
        BigDecimal sum = BigDecimal.ZERO;
        for (final OrderItem item : items) {
            sum = sum.add(item.getLineTotal());
        }
        this.total = sum;
    }

    public void markCancelled() {
        this.status = OrderStatus.CANCELLED;
    }

    @PrePersist
    void onPersist() {
        final Instant now = Instant.now();
        if (this.createdAt == null) {
            this.createdAt = now;
        }
        if (this.updatedAt == null) {
            this.updatedAt = now;
        }
    }

    @PreUpdate
    void onUpdate() {
        this.updatedAt = Instant.now();
    }

    public Long getId() { return id; }
    public String getCustomerReference() { return customerReference; }
    public OrderStatus getStatus() { return status; }
    public BigDecimal getTotal() { return total; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }

    /** Read-only view; mutate via {@link #addItem(OrderItem)}. */
    public List<OrderItem> getItems() {
        return Collections.unmodifiableList(items);
    }

    @Override
    public String toString() {
        return "Order{id=" + id + ", status=" + status + ", total=" + total + '}';
    }
}
