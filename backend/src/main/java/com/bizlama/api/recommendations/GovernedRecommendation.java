package com.bizlama.api.recommendations;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record GovernedRecommendation(
        String id,
        String kitchenId,
        String locationId,
        Type type,
        String ingredientId,
        String dishId,
        String recipeVersionId,
        BigDecimal proposedQuantity,
        String unit,
        String calculationId,
        BigDecimal confidence,
        String confidenceComponentsJson,
        String reasonCode,
        RiskTier riskTier,
        Status status,
        int version,
        Instant expiresAt,
        Instant createdAt,
        String createdBy,
        Instant updatedAt,
        Instant decidedAt,
        String decidedBy,
        String decisionReason,
        Instant appliedAt,
        String appliedBy,
        String appliedActionType,
        String appliedActionId,
        String supersedesRecommendationId,
        String supersededByRecommendationId,
        Instant reversedAt,
        String reversedBy,
        String reversalReason
) {
    public enum Type {
        PURCHASE,
        PREPARE,
        UTILISE_EXPIRING_STOCK,
        REDUCE_OR_AVOID_PURCHASE
    }

    public enum RiskTier {
        LOW,
        MEDIUM,
        HIGH
    }

    public enum Status {
        PENDING,
        APPROVED,
        DISMISSED,
        INVENTORY_FLAGGED,
        APPLIED,
        EXPIRED,
        SUPERSEDED,
        REVERSED
    }

    public enum OutcomeType {
        PREPARED,
        SOLD,
        FULFILLED,
        WASTED,
        EMERGENCY_PURCHASED,
        STOCKOUT,
        OVERRIDE,
        CORRECTED_INVENTORY
    }

    public record GenerationResult(
            String calculationId,
            DemandCalculation calculation,
            List<GovernedRecommendation> recommendations
    ) {
    }

    public record Outcome(
            String id,
            String recommendationId,
            OutcomeType type,
            BigDecimal quantity,
            String unit,
            String sourceReferenceType,
            String sourceReferenceId,
            String notes,
            Instant occurredAt,
            Instant recordedAt,
            String recordedBy
    ) {
    }
}
