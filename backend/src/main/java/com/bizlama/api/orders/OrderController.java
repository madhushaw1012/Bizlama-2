package com.bizlama.api.orders;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import com.bizlama.api.common.PageResponse;
import com.bizlama.api.domain.Dish;
import com.bizlama.api.domain.Order;
import com.bizlama.api.domain.Order.OrderItem;
import com.bizlama.api.domain.OrderListItem;
import com.bizlama.api.domain.OrderSummary;
import com.bizlama.api.store.OperationalRepository;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

@RestController
@RequestMapping("/api/orders")
public class OrderController {

    private final OperationalRepository store;

    public OrderController(OperationalRepository store) {
        this.store = store;
    }

    @GetMapping
    public List<Order> list() {
        return store.orders();
    }

    @GetMapping("/search")
    public PageResponse<OrderListItem> search(
            @RequestParam(defaultValue = "") String query,
            @RequestParam(defaultValue = "all") String status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        int safePage = Math.max(0, page);
        int safeSize = Math.max(1, Math.min(size, 100));

        return PageResponse.of(
                store.searchOrders(query, status, safePage, safeSize),
                safePage,
                safeSize,
                store.countOrders(query, status)
        );
    }

    @GetMapping("/summary")
    public OrderSummary summary() {
        return store.orderSummary();
    }

    @GetMapping("/{id}")
    public Order detail(@PathVariable String id) {
        return store.order(id)
                .orElseThrow(() ->
                        new ResponseStatusException(
                                HttpStatus.NOT_FOUND,
                                "Order not found."
                        ));
    }

    @PatchMapping("/{id}/status")
    public Order updateStatus(
            @PathVariable String id,
            @Valid @RequestBody UpdateStatusRequest request,
            @AuthenticationPrincipal Jwt identity) {

        return store.updateOrderStatus(
                id,
                request.status(),
                identity == null ? "order-api" : identity.getSubject()
        );
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Order create(
            @Valid @RequestBody CreateOrderRequest request) {

        List<OrderItem> items = request.items()
                .stream()
                .map(item -> {
                    Dish dish = store.dish(item.dishId())
                            .orElseThrow(() ->
                                    new ResponseStatusException(
                                            HttpStatus.NOT_FOUND,
                                            "Dish not found: "+ item.dishId()));

                    String recipeVersionId = dish.activeRecipeVersionId();
                    if (recipeVersionId == null
                            || store.recipe(recipeVersionId).isEmpty()) {
                        throw new ResponseStatusException(
                                HttpStatus.UNPROCESSABLE_ENTITY,
                                "Dish has no active recipe: " + dish.name()
                        );
                    }

                    return new OrderItem(
                            dish.id(),
                            recipeVersionId,
                            item.quantity(),
                            dish.price()
                    );
                })
                .toList();

        BigDecimal total = items.stream()
                .map(item ->
                        item.unitPrice()
                                .multiply(
                                        BigDecimal.valueOf(item.quantity())
                                ))
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        String id = "ORD-"
                + UUID.randomUUID()
                        .toString()
                        .substring(0, 8)
                        .toUpperCase();

        return store.saveOrder(
                new Order(
                        id,
                        items,
                        total,
                        Order.Status.QUEUED,
                        Instant.now()
                )
        );
    }

    public record CreateOrderRequest(
            @NotEmpty
            List<@Valid ItemRequest> items
    ) {
    }

    public record ItemRequest(
            @NotBlank String dishId,
            @Positive int quantity
    ) {
    }

    public record UpdateStatusRequest(
            @NotNull Order.Status status
    ) {
    }
}