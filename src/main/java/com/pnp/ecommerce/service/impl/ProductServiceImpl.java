package com.pnp.ecommerce.service.impl;

import com.pnp.ecommerce.dto.PageResponse;
import com.pnp.ecommerce.dto.ProductRequest;
import com.pnp.ecommerce.dto.ProductResponse;
import com.pnp.ecommerce.entity.Product;
import com.pnp.ecommerce.exception.ProductNotFoundException;
import com.pnp.ecommerce.mapper.ProductMapper;
import com.pnp.ecommerce.repository.ProductRepository;
import com.pnp.ecommerce.service.ProductService;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Default {@link ProductService} implementation.
 *
 * <p>Why constructor injection (vs. field injection): constructor
 * injection is the Spring team's own recommendation — it makes
 * dependencies explicit, enables {@code final} fields, and allows the
 * class to be instantiated in unit tests without Spring or reflection.
 *
 * <p>Transactional strategy:
 * <ul>
 *   <li>{@code create}: {@code @Transactional} — default
 *       {@code REQUIRED} propagation and {@code READ_COMMITTED}
 *       isolation. One INSERT, rolled back on any thrown
 *       {@link RuntimeException}.</li>
 *   <li>{@code getById} / {@code list}: {@code @Transactional(readOnly=true)}
 *       — signals the JDBC driver + Hibernate session that no dirty
 *       checks or flushes are needed, cheaper than a read-write tx.</li>
 * </ul>
 */
@Service
public class ProductServiceImpl implements ProductService {

    private final ProductRepository productRepository;

    public ProductServiceImpl(final ProductRepository productRepository) {
        this.productRepository = productRepository;
    }

    /**
     * Transactional guarantee: single-row INSERT inside a
     * {@code REQUIRED} transaction. No pessimistic locks are required
     * because a brand-new row has no concurrent readers. Any exception
     * (including DB CHECK violations translated by Spring) rolls the
     * tx back.
     *
     * <p>Query classification: ORM-driven. {@code save} issues a JPA
     * {@code EntityManager.persist} which Hibernate translates to a
     * parameterised INSERT. Native SQL would buy nothing and would
     * lose the managed-entity that {@link ProductMapper#toResponse}
     * expects as input.
     */
    @Override
    @Transactional
    public ProductResponse create(final ProductRequest request) {
        final Product saved = productRepository.save(ProductMapper.toEntity(request));
        return ProductMapper.toResponse(saved);
    }

    @Override
    @Transactional(readOnly = true)
    public ProductResponse getById(final long id) {
        final Product product = productRepository.findById(id)
                .orElseThrow(() -> new ProductNotFoundException(id));
        return ProductMapper.toResponse(product);
    }

    @Override
    @Transactional(readOnly = true)
    public PageResponse<ProductResponse> list(final Pageable pageable) {
        return PageResponse.from(
                productRepository.findAll(pageable).map(ProductMapper::toResponse));
    }
}
