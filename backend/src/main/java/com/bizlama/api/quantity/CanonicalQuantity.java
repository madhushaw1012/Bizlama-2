package com.bizlama.api.quantity;

import java.math.BigDecimal;

public record CanonicalQuantity(
        BigDecimal quantity,
        String unit,
        UnitDimension dimension
) {
}
