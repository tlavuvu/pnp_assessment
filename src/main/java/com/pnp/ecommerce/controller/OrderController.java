package com.pnp.ecommerce.controller;

import com.pnp.ecommerce.dto.CreateOrderRequest;
import com.pnp.ecommerce.dto.OrderResponse;
import com.pnp.ecommerce.service.OrderService;
import jakarta.validation.Valid;
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
 * REST entry points for order placement, lookup, and cancellation.
 *
 * <p>Depends only on {@link OrderService} (the interface) — same DIP
 * reasoning as {@link ProductController}.
 */
@RestController
@RequestMapping(path = "/api/v1/orders")
public class OrderController {

    private final OrderService orderService;

    public OrderController(final OrderService orderService) {
        this.orderService = orderService;
    }

    @PostMapping
    public ResponseEntity<OrderResponse> placeOrder(
            @Valid @RequestBody final CreateOrderRequest request) {
        final OrderResponse created = orderService.placeOrder(request);
        final URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}")
                .buildAndExpand(created.id())
                .toUri();
        return ResponseEntity.created(location).body(created);
    }

    @GetMapping(path = "/{id}")
    public OrderResponse getById(@PathVariable final long id) {
        return orderService.getById(id);
    }

    /**
     * Why {@code POST /orders/&#123;id&#125;/cancel} (vs. {@code DELETE}):
     * the underlying row is not deleted — it transitions to
     * {@code CANCELLED} and still participates in reports. A
     * {@code DELETE} on a logical state transition would mislead any
     * caller reading the URL in a log. Documented in
     * {@code docs/API-DESIGN.md}.
     *
     * <p>Returns 200 + the cancelled {@link OrderResponse} so callers
     * can confirm the new status without an extra GET. Non-idempotent
     * by design (ADR-013): a second call on an already-cancelled
     * order returns HTTP 409 / {@code ORDER_NOT_CANCELLABLE}.
     */
    @PostMapping(path = "/{id}/cancel")
    public OrderResponse cancel(@PathVariable final long id) {
        return orderService.cancel(id);
    }
}
