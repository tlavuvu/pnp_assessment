package com.pnp.ecommerce.repository;

import com.pnp.ecommerce.entity.Order;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * Spring Data JPA repository for {@link Order}.
 *
 * <p>Why Spring Data: same reasoning as {@code ProductRepository} —
 * standard CRUD with one custom JPQL fetch-join to avoid the classic
 * N+1 when the order detail endpoint reads items + product names.
 */
@Repository
public interface OrderRepository extends JpaRepository<Order, Long> {

    /**
     * Fetch an order with its lines and each line's product hydrated in
     * a single SQL statement.
     *
     * <p><b>Query classification:</b> ORM-driven (JPQL with explicit
     * {@code JOIN FETCH}). Chosen over native SQL because we want a
     * managed entity graph as the return type — DTO assembly happens
     * in the mapper layer (Phase 5). {@code DISTINCT} avoids row
     * multiplication caused by the items collection join.
     *
     * <p>This method does NOT take a lock; it's intended for read-only
     * detail GETs. Stock-touching reads use
     * {@link ProductRepository#findAllByIdForUpdateOrdered} instead.
     */
    @Query("""
            SELECT DISTINCT o FROM Order o
            LEFT JOIN FETCH o.items i
            LEFT JOIN FETCH i.product
            WHERE o.id = :id
            """)
    Optional<Order> findByIdWithItems(@Param("id") Long id);
}
