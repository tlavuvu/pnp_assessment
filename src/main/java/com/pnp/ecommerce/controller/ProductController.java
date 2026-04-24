package com.pnp.ecommerce.controller;

import com.pnp.ecommerce.dto.PageResponse;
import com.pnp.ecommerce.dto.ProductRequest;
import com.pnp.ecommerce.dto.ProductResponse;
import com.pnp.ecommerce.service.ProductService;
import jakarta.validation.Valid;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.net.URI;

/**
 * REST entry points for the product catalogue.
 *
 * <p>Why the controller depends only on {@link ProductService} (the
 * interface) rather than {@code ProductServiceImpl}: mandated by the
 * service-layer rule and by the Dependency Inversion Principle —
 * controllers must know the contract, not the implementation. This
 * also lets MockMvc tests swap the bean with a Mockito mock without
 * touching Spring's DI graph.
 *
 * <p>No business logic lives here. The only "decisions" the
 * controller makes are:
 * <ul>
 *   <li>Which HTTP status code to return (201 vs 200).</li>
 *   <li>How to build the {@code Location} header on create.</li>
 *   <li>Which DTO shape comes in / goes out.</li>
 * </ul>
 */
@RestController
@RequestMapping(path = "/api/v1/products")
public class ProductController {

    private final ProductService productService;

    public ProductController(final ProductService productService) {
        this.productService = productService;
    }

    /**
     * Why 201 + {@code Location} header (vs. plain 200): HTTP-correct
     * for a resource-creation POST. A {@code Location: /api/v1/products/42}
     * lets clients follow the spec without parsing the body.
     */
    @PostMapping
    public ResponseEntity<ProductResponse> create(@Valid @RequestBody final ProductRequest request) {
        final ProductResponse created = productService.create(request);
        final URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}")
                .buildAndExpand(created.id())
                .toUri();
        return ResponseEntity.created(location).body(created);
    }

    @GetMapping(path = "/{id}")
    public ProductResponse getById(@PathVariable final long id) {
        return productService.getById(id);
    }

    /**
     * Query params {@code page}, {@code size}, {@code sort} are bound
     * into a Spring Data {@link Pageable} by the framework. Response
     * shape is our stable {@link PageResponse} envelope, not Spring's
     * version-unstable Page JSON (see {@link PageResponse} javadoc).
     */
    @GetMapping
    public PageResponse<ProductResponse> list(final Pageable pageable) {
        return productService.list(pageable);
    }
}
