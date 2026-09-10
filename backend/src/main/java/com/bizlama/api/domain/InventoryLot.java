package com.bizlama.api.domain;

import java.math.BigDecimal;
import java.time.LocalDate;

public record InventoryLot (
    String id,
    String ingredientId,
    String ingredientName,
    BigDecimal quantityRemaining,
    String unit,
    LocalDate purchasedAt,
    LocalDate expiresAt,
    String source,
    String status
) {

}
