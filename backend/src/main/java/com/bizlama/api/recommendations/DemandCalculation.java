package com.bizlama.api.recommendations;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * Numeric, reproducible evidence for a demand horizon. No explanatory text in
 * this contract is authoritative for a quantity; every number is calculated by
 * {@link DemandCalculationService} from operational rows.
 */
public record DemandCalculation(
        DemandScope scope,
        LocalDate asOfKitchenDate,
        LocalDate horizonEndKitchenDate,
        Instant calculatedAt,
        String calculationMethod,
        List<String> includedOrderStatuses,
        List<IngredientDemand> ingredients,
        List<PreparationDemand> preparations,
        List<UnresolvedOrderEvidence> unresolvedOrderEvidence
) {
    public static final String METHOD = "DETERMINISTIC_DEMAND_V2_PROVENANCE_GATED";

    public record DemandScope(
            String kitchenId,
            String locationId,
            Instant horizonStart,
            Instant horizonEnd,
            Instant asOf
    ) {
    }

    public record IngredientDemand(
            String ingredientId,
            String ingredientName,
            String canonicalUnit,
            BigDecimal grossDemand,
            BigDecimal onHandUsableSupply,
            BigDecimal horizonEndSurvivingSupply,
            BigDecimal usableSupply,
            BigDecimal safetyStock,
            String safetyStockSource,
            BigDecimal shortage,
            BigDecimal timePhasedShortfall,
            BigDecimal expiryRiskSurplus,
            boolean demandEvidenceComplete,
            List<String> contributingOrderIds,
            List<String> contributingRecipeVersionIds,
            List<OrderDemandEvidence> orderContributions,
            List<LotEvidence> contributingLots,
            List<ExcludedLotEvidence> excludedLots
    ) {
    }

    public record OrderDemandEvidence(
            String orderId,
            int lineNumber,
            String dishId,
            String recipeVersionId,
            String recipePinProvenance,
            String recipeYieldProvenance,
            int orderedQuantity,
            int preparedQuantity,
            int remainingQuantity,
            Instant orderedAt,
            Instant requiredAt,
            BigDecimal recipeIngredientQuantity,
            String recipeIngredientUnit,
            BigDecimal recipeYieldQuantity,
            String recipeYieldUnit,
            BigDecimal canonicalDemand,
            String canonicalUnit
    ) {
    }

    public record LotEvidence(
            String lotId,
            BigDecimal storedQuantity,
            String storedUnit,
            BigDecimal canonicalQuantity,
            String canonicalUnit,
            LocalDate purchasedAt,
            LocalDate expiresAt,
            String expiryProvenance,
            BigDecimal allocatedDemand,
            BigDecimal remainingAfterDemand,
            BigDecimal expiryRiskQuantity
    ) {
    }

    public record UnresolvedOrderEvidence(
            String orderId,
            int lineNumber,
            String dishId,
            String provisionalRecipeVersionId,
            String recipePinProvenance,
            String recipeYieldProvenance,
            int orderedQuantity,
            int preparedQuantity,
            Instant orderedAt,
            Instant requiredAt,
            List<String> potentiallyAffectedIngredientIds,
            String resolutionCode
    ) {
    }

    public record ExcludedLotEvidence(
            String lotId,
            BigDecimal storedQuantity,
            String storedUnit,
            LocalDate expiresAt,
            String status,
            String reason
    ) {
    }

    public record PreparationDemand(
            String dishId,
            String dishName,
            String recipeVersionId,
            BigDecimal quantity,
            String unit,
            List<String> contributingOrderIds,
            List<String> requiredIngredientIds
    ) {
    }

    public record SafetyStockSetting(
            String kitchenId,
            String locationId,
            String ingredientId,
            BigDecimal quantity,
            String unit,
            int version,
            Instant updatedAt,
            String updatedBy
    ) {
    }
}
