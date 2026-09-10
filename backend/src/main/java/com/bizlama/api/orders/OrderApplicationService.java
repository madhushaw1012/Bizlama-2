package com.bizlama.api.orders;

import com.bizlama.api.domain.Dish;
import com.bizlama.api.domain.Order;
import com.bizlama.api.domain.Order.OrderItem;
import com.bizlama.api.store.OperationalRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** Shared transactional boundary for API and Record Activity order capture. */
@Service
public class OrderApplicationService {

    private final OperationalRepository repository;

    public OrderApplicationService(OperationalRepository repository) {
        this.repository = repository;
    }

    public Order create(List<Line> lines) {
        return create(lines, Instant.now());
    }

    @Transactional
    public Order create(List<Line> lines, Instant createdAt) {
        if (lines == null || lines.isEmpty()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "At least one order item is required."
            );
        }
        if (createdAt == null) {
            throw new IllegalArgumentException("Order time is required.");
        }

        List<OrderItem> items = lines.stream()
                .map(line -> toOrderItem(line))
                .toList();
        BigDecimal total = items.stream()
                .map(item -> item.unitPrice().multiply(
                        BigDecimal.valueOf(item.quantity())
                ))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        String id = "ORD-" + UUID.randomUUID()
                .toString()
                .substring(0, 8)
                .toUpperCase();

        return repository.saveOrder(new Order(
                id,
                items,
                total,
                Order.Status.QUEUED,
                createdAt
        ));
    }

    private OrderItem toOrderItem(Line line) {
        if (line == null || line.dishId() == null
                || line.dishId().isBlank()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Every order item requires a dish."
            );
        }
        if (line.quantity() <= 0) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Order quantities must be positive."
            );
        }
        Dish dish = repository.dish(line.dishId())
                .filter(Dish::active)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "Active dish not found: " + line.dishId()
                ));
        String recipeVersionId = dish.activeRecipeVersionId();
        if (recipeVersionId == null
                || repository.recipe(recipeVersionId)
                .filter(recipe -> recipe.active()
                        && dish.id().equals(recipe.dishId()))
                .isEmpty()) {
            throw new ResponseStatusException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "Dish has no active recipe: " + dish.name()
            );
        }
        return new OrderItem(
                dish.id(), recipeVersionId, line.quantity(), dish.price()
        );
    }

    public record Line(String dishId, int quantity) {
    }
}
