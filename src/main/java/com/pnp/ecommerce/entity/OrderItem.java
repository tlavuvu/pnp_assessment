package com.pnp.ecommerce.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * Line within an {@link Order}. Two snapshot fields make history stable:
 *
 * <ul>
 *   <li>{@code unitPrice} — copied from {@code product.price} at order
 *       time. Subsequent product price changes do not rewrite history.</li>
 *   <li>{@code lineTotal} — equal to {@code quantity * unitPrice}, also
 *       enforced by a DB CHECK constraint (see {@code 003-create-order-item.sql}).
 *       Storing it makes report aggregations a pure {@code SUM(...)}.</li>
 * </ul>
 *
 * <p>Why eager FK columns ({@code order_id}, {@code product_id}) but
 * lazy entity fetch: we want the foreign-key value available without a
 * SELECT (so we can build a response from {@code product.id} alone),
 * but we don't want the {@link Product} hydrated unless the caller
 * actually reads {@link #getProduct()}.
 */
@Entity
@Table(name = "order_item")
public class OrderItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "order_id", nullable = false)
    private Order order;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "product_id", nullable = false)
    private Product product;

    @Column(name = "quantity", nullable = false)
    private Integer quantity;

    @Column(name = "unit_price", nullable = false, precision = 12, scale = 2)
    private BigDecimal unitPrice;

    @Column(name = "line_total", nullable = false, precision = 12, scale = 2)
    private BigDecimal lineTotal;

    /** Required by JPA. Should not be called from application code. */
    protected OrderItem() {
        // intentionally empty
    }

    /**
     * Construct a line and compute its {@code lineTotal} eagerly.
     * Doing the multiplication here (instead of in the service) keeps
     * the invariant local to the entity that owns it and matches the
     * DB CHECK constraint exactly.
     */
    public OrderItem(final Product product, final int quantity, final BigDecimal unitPriceSnapshot) {
        this.product = Objects.requireNonNull(product, "product");
        if (quantity <= 0) {
            throw new IllegalArgumentException("quantity must be positive");
        }
        this.quantity = quantity;
        this.unitPrice = Objects.requireNonNull(unitPriceSnapshot, "unitPriceSnapshot");
        this.lineTotal = unitPriceSnapshot.multiply(BigDecimal.valueOf(quantity));
    }

    /**
     * Wire the back-reference. Package-private and intended to be
     * called only from {@link Order#addItem(OrderItem)} so the two
     * sides of the relationship cannot drift.
     */
    void setOrder(final Order order) {
        this.order = order;
    }

    public Long getId() { return id; }
    public Order getOrder() { return order; }
    public Product getProduct() { return product; }
    public Integer getQuantity() { return quantity; }
    public BigDecimal getUnitPrice() { return unitPrice; }
    public BigDecimal getLineTotal() { return lineTotal; }

    @Override
    public String toString() {
        return "OrderItem{id=" + id + ", quantity=" + quantity + ", lineTotal=" + lineTotal + '}';
    }
}
