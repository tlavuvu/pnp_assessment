package com.pnp.ecommerce.service;

import com.pnp.ecommerce.dto.PageResponse;
import com.pnp.ecommerce.dto.ProductRequest;
import com.pnp.ecommerce.dto.ProductResponse;
import org.springframework.data.domain.Pageable;

/**
 * Contract for product-catalogue operations.
 *
 * <p>Why an interface: the mandatory service-layer rule
 * ({@code .cursorrules} + prompt §7) requires controllers to depend on
 * interfaces so the wiring satisfies the Dependency Inversion
 * Principle and so tests can swap the implementation without Spring.
 *
 * <p>No transactional or security annotations appear here — contracts
 * describe <em>what</em>, not <em>how</em>. The {@code @Transactional}
 * boundary and method-level security belong on the implementation
 * ({@link com.pnp.ecommerce.service.impl.ProductServiceImpl}).
 */
public interface ProductService {

    ProductResponse create(ProductRequest request);

    ProductResponse getById(long id);

    PageResponse<ProductResponse> list(Pageable pageable);
}
