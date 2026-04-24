package com.pnp.ecommerce.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;

/**
 * Catalogue product. Owns price and stock; both are mutated only inside
 * a {@code @Transactional} service method that has already taken a
 * pessimistic row lock on this row (see {@code ARCHITECTURE.md} §6,
 * ADR-015).
 *
 * <p>Why a class instead of a Java {@code record}: JPA entities require
 * a no-arg constructor, mutable state for dirty-checking, and the
 * ability to be proxied by Hibernate. Records satisfy none of these.
 *
 * <p>Why no {@code @Version} / optimistic locking: ADR-015 chose
 * pessimistic row locking as the concurrency strategy. Adding
 * {@code @Version} on top would force the service to own a retry loop
 * for no additional safety.
 */
@Entity
@Table(name = "product")
public class Product {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "name", nullable = false, length = 120)
    private String name;

    @Column(name = "description", length = 500)
    private String description;

    @Column(name = "price", nullable = false, precision = 12, scale = 2)
    private BigDecimal price;

    @Column(name = "stock", nullable = false)
    private Integer stock;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** Required by JPA. Should not be called from application code. */
    protected Product() {
        // intentionally empty
    }

    public Product(final String name, final String description,
                   final BigDecimal price, final Integer stock) {
        this.name = Objects.requireNonNull(name, "name");
        this.description = description;
        this.price = Objects.requireNonNull(price, "price");
        this.stock = Objects.requireNonNull(stock, "stock");
    }

    /**
     * Decrement stock by {@code quantity}. The caller MUST have already
     * taken a pessimistic write lock on this row in the same transaction
     * (see {@code ProductRepository#findAllByIdForUpdateOrdered}). This
     * method does no locking of its own.
     *
     * @throws IllegalStateException if {@code quantity > stock}; the
     *         service layer is expected to translate this into the
     *         domain-level {@code InsufficientStockException} so
     *         controllers can map it to HTTP 409.
     */
    public void decrementStock(final int quantity) {
        if (quantity <= 0) {
            throw new IllegalArgumentException("quantity must be positive");
        }
        if (this.stock < quantity) {
            throw new IllegalStateException(
                    "insufficient stock: requested " + quantity + ", available " + this.stock);
        }
        this.stock -= quantity;
    }

    /** Restore stock on cancellation. Same locking expectations as decrement. */
    public void incrementStock(final int quantity) {
        if (quantity <= 0) {
            throw new IllegalArgumentException("quantity must be positive");
        }
        this.stock += quantity;
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
    public String getName() { return name; }
    public String getDescription() { return description; }
    public BigDecimal getPrice() { return price; }
    public Integer getStock() { return stock; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }

    @Override
    public String toString() {
        return "Product{id=" + id + ", name='" + name + "', stock=" + stock + '}';
    }
}
