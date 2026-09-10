package com.bizlama.api.domain;

import java.math.BigDecimal;
import java.time.Instant;

public record StockMovement (
    String id,
    String stockLotId,
    String ingredientId,
    MovementType type,
    BigDecimal quantityChange,
    String unit,
    String referenceType,
    String referenceId,
    Instant occurredAt
) {
    public enum MovementType {
        PURCHASE,
        PRODUCTION_CONSUMPTION,
        WASTE,
        MANUAL_ADJUSTMENT,
        EXPIRY_WRITE_OFF,
        REVERSAL,
        CORRECTION
    }
}
