package com.bizlama.api.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record RecipeVersion(
        String id,
        String dishId,
        int versionNumber,
        List<RecipeIngredient> ingredients,
        List<String> instructions,
        BigDecimal yieldQuantity,
        String yieldUnit,
        String yieldProvenance,
        String changeReason,
        Instant createdAt,
        String createdBy,
        boolean active,
        String approvedBy,
        Instant approvedAt,
        Instant effectiveAt,
        Instant supersededAt,
        String supersededByVersionId
) {
    public record RecipeIngredient(
            String ingredientId,
            BigDecimal quantity,
            String unit
    ) {
    }
}
