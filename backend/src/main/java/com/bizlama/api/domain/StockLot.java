package com.bizlama.api.domain;

import java.math.BigDecimal;
import java.time.LocalDate;

public record StockLot (
    String id,
    String ingredientId,
    BigDecimal quantityRemaining,
    String unit,
    LocalDate purchasedAt,
    LocalDate expiresAt,
    String source
) {
}
