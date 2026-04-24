package com.pnp.ecommerce.mapper;

import com.pnp.ecommerce.dto.ProductRequest;
import com.pnp.ecommerce.dto.ProductResponse;
import com.pnp.ecommerce.entity.Product;

/**
 * Pure translation between {@link ProductRequest} / {@link ProductResponse}
 * and the {@link Product} entity.
 *
 * <p>Why hand-rolled static methods (vs. MapStruct or ModelMapper):
 *
 * <ul>
 *   <li>Three mappings total in the whole app — an annotation processor
 *       + generated-sources directory is more reviewer cognitive load
 *       than the code it replaces.</li>
 *   <li>Static methods are trivially testable without Spring context.</li>
 *   <li>Private constructor + {@code final} class makes misuse
 *       impossible.</li>
 * </ul>
 *
 * <p>If the mapping footprint ever exceeds ~10 entities, switch to
 * MapStruct and let this class be the first migration target.
 */
public final class ProductMapper {

    private ProductMapper() {
        // utility class
    }

    public static Product toEntity(final ProductRequest request) {
        return new Product(
                request.name(),
                request.description(),
                request.price(),
                request.stock()
        );
    }

    public static ProductResponse toResponse(final Product product) {
        return new ProductResponse(
                product.getId(),
                product.getName(),
                product.getDescription(),
                product.getPrice(),
                product.getStock(),
                product.getCreatedAt(),
                product.getUpdatedAt()
        );
    }
}
