package com.bizlama.api.stock;

import java.math.BigDecimal;

public class InsufficientStockException extends IllegalStateException {

    private final String ingredientId;
    private final BigDecimal requested;
    private final BigDecimal available;
    private final BigDecimal shortfall;
    private final String unit;

    public InsufficientStockException(
            String ingredientId,
            BigDecimal requested,
            BigDecimal available,
            String unit
    ) {
        super("Insufficient usable stock for " + ingredientId + ": short by "
                + requested.subtract(available).stripTrailingZeros().toPlainString()
                + " " + unit);
        this.ingredientId = ingredientId;
        this.requested = requested;
        this.available = available;
        this.shortfall = requested.subtract(available);
        this.unit = unit;
    }

    public String ingredientId() {
        return ingredientId;
    }

    public BigDecimal requested() {
        return requested;
    }

    public BigDecimal available() {
        return available;
    }

    public BigDecimal shortfall() {
        return shortfall;
    }

    public String unit() {
        return unit;
    }
}
