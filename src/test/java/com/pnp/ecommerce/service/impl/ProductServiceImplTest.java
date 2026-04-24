package com.pnp.ecommerce.service.impl;

import com.pnp.ecommerce.dto.PageResponse;
import com.pnp.ecommerce.dto.ProductRequest;
import com.pnp.ecommerce.dto.ProductResponse;
import com.pnp.ecommerce.entity.Product;
import com.pnp.ecommerce.exception.ProductNotFoundException;
import com.pnp.ecommerce.repository.ProductRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link ProductServiceImpl}.
 *
 * <p>Why pure Mockito (no Spring context): the service owns no
 * behaviour that requires a container — only mapper invocation +
 * delegation to the repository. Booting Spring would slow the feedback
 * loop from ~50 ms to 3+ s for zero additional coverage.
 */
@ExtendWith(MockitoExtension.class)
class ProductServiceImplTest {

    @Mock
    private ProductRepository productRepository;

    @InjectMocks
    private ProductServiceImpl service;

    @Test
    @DisplayName("create: persists entity from request and returns mapped response")
    void createPersistsAndMaps() {
        final ProductRequest request = new ProductRequest(
                "Widget", "A useful widget", new BigDecimal("19.99"), 100);
        final Product persisted = product(7L, "Widget", new BigDecimal("19.99"), 100);
        when(productRepository.save(any(Product.class))).thenReturn(persisted);

        final ProductResponse response = service.create(request);

        final ArgumentCaptor<Product> captor = ArgumentCaptor.forClass(Product.class);
        verify(productRepository).save(captor.capture());
        assertThat(captor.getValue().getName()).isEqualTo("Widget");
        assertThat(captor.getValue().getPrice()).isEqualByComparingTo("19.99");
        assertThat(captor.getValue().getStock()).isEqualTo(100);

        assertThat(response.id()).isEqualTo(7L);
        assertThat(response.name()).isEqualTo("Widget");
        assertThat(response.price()).isEqualByComparingTo("19.99");
    }

    @Test
    @DisplayName("getById: returns mapped response when product exists")
    void getByIdReturnsResponse() {
        final Product persisted = product(42L, "Thing", new BigDecimal("1.00"), 1);
        when(productRepository.findById(42L)).thenReturn(Optional.of(persisted));

        final ProductResponse response = service.getById(42L);

        assertThat(response.id()).isEqualTo(42L);
        assertThat(response.stock()).isEqualTo(1);
    }

    @Test
    @DisplayName("getById: throws ProductNotFoundException when product missing")
    void getByIdMissingThrows() {
        when(productRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getById(99L))
                .isInstanceOf(ProductNotFoundException.class)
                .hasMessageContaining("99");
    }

    @Test
    @DisplayName("list: delegates to repository and wraps into PageResponse envelope")
    void listWrapsPage() {
        final Product a = product(1L, "A", new BigDecimal("1.00"), 10);
        final Product b = product(2L, "B", new BigDecimal("2.00"), 20);
        final Page<Product> page = new PageImpl<>(List.of(a, b), PageRequest.of(0, 20), 2);
        when(productRepository.findAll(any(PageRequest.class))).thenReturn(page);

        final PageResponse<ProductResponse> response = service.list(PageRequest.of(0, 20));

        assertThat(response.content()).hasSize(2);
        assertThat(response.totalElements()).isEqualTo(2);
        assertThat(response.page()).isZero();
        assertThat(response.size()).isEqualTo(20);
        assertThat(response.totalPages()).isEqualTo(1);
    }

    private static Product product(final Long id,
                                   final String name,
                                   final BigDecimal price,
                                   final int stock) {
        final Product p = new Product(name, null, price, stock);
        ReflectionTestUtils.setField(p, "id", id);
        ReflectionTestUtils.setField(p, "createdAt", Instant.now());
        ReflectionTestUtils.setField(p, "updatedAt", Instant.now());
        return p;
    }
}
