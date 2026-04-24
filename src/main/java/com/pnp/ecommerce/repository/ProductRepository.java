package com.pnp.ecommerce.repository;

import com.pnp.ecommerce.entity.Product;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;

/**
 * Spring Data JPA repository for {@link Product}.
 *
 * <p>Why a Spring Data interface and not a hand-rolled class: CRUD
 * operations on {@code Product} are entirely standard. Spring Data
 * gives them to us with zero implementation code while still letting
 * us add bespoke locking queries below. A hand-rolled class would be
 * boilerplate for no behavioural gain.
 */
@Repository
public interface ProductRepository extends JpaRepository<Product, Long> {

    /**
     * Load every product whose id is in {@code ids} and take a
     * pessimistic write lock on each row, ordered ascending by id to
     * eliminate deadlocks when two concurrent orders touch overlapping
     * product sets (ARCHITECTURE.md §6, ADR-015).
     *
     * <p><b>Query classification:</b> ORM-driven (JPQL). Chosen over
     * native SQL because the result is a managed entity that we will
     * mutate in place ({@link Product#decrementStock(int)} /
     * {@link Product#incrementStock(int)}) inside the same transaction,
     * letting Hibernate's dirty-check flush the UPDATE on commit.
     *
     * <p><b>Transactional guarantee:</b> {@code @Lock(PESSIMISTIC_WRITE)}
     * translates to {@code SELECT ... FOR UPDATE} on Postgres. The
     * lock is held until the surrounding {@code @Transactional} commits
     * or rolls back. Callers MUST be inside such a transaction; a call
     * outside one will not actually lock anything (Hibernate logs a
     * warning).
     *
     * <p>Why pass a {@link Collection} (not a {@code List}) and order
     * inside the query: callers may pass any collection (Set, etc.),
     * and we centralise the lock-order rule here so no caller can
     * forget it.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT p FROM Product p WHERE p.id IN :ids ORDER BY p.id ASC")
    List<Product> findAllByIdForUpdateOrdered(@Param("ids") Collection<Long> ids);
}
