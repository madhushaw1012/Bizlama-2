package com.bizlama.api.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record Order (
    String id,
    List<OrderItem> items,
    BigDecimal total,
    Status status,
    Instant createdAt
) {
    public enum Status {
       QUEUED,
       PREPARING,
       DONE,
        CANCELLED
    }
    public record OrderItem (
        String dishId,
        String recipeVersionId,
        int quantity,
        BigDecimal unitPrice
    ) {}
}
