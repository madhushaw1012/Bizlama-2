export type RecommendationType =
    | 'PURCHASE'
    | 'PREPARE'
    | 'UTILISE_EXPIRING_STOCK'
    | 'REDUCE_OR_AVOID_PURCHASE';

export type RecommendationStatus =
    | 'PENDING'
    | 'APPROVED'
    | 'DISMISSED'
    | 'INVENTORY_FLAGGED'
    | 'APPLIED'
    | 'EXPIRED'
    | 'SUPERSEDED'
    | 'REVERSED';

export type RecommendationOutcomeType =
    | 'PREPARED'
    | 'SOLD'
    | 'FULFILLED'
    | 'WASTED'
    | 'EMERGENCY_PURCHASED'
    | 'STOCKOUT'
    | 'OVERRIDE'
    | 'CORRECTED_INVENTORY';

export interface GovernedRecommendation {
    id: string;
    kitchenId: string;
    locationId: string;
    type: RecommendationType;
    ingredientId?: string;
    dishId?: string;
    recipeVersionId?: string;
    proposedQuantity: number;
    unit: string;
    calculationId: string;
    confidence: number;
    reasonCode: string;
    riskTier: 'LOW' | 'MEDIUM' | 'HIGH';
    status: RecommendationStatus;
    version: number;
    expiresAt: string;
    createdAt: string;
    decidedAt?: string;
    decisionReason?: string;
    appliedAt?: string;
    appliedActionType?: string;
    appliedActionId?: string;
    reversedAt?: string;
    reversedBy?: string;
    reversalReason?: string;
}

export interface DemandCalculation {
    scope: {
        kitchenId: string;
        locationId: string;
        horizonStart: string;
        horizonEnd: string;
        asOf: string;
    };
    asOfKitchenDate: string;
    horizonEndKitchenDate: string;
    calculatedAt: string;
    calculationMethod: string;
    includedOrderStatuses: string[];
    ingredients: IngredientDemand[];
    preparations: PreparationDemand[];
    unresolvedOrderEvidence: UnresolvedOrderEvidence[];
}

export interface IngredientDemand {
    ingredientId: string;
    ingredientName: string;
    canonicalUnit: string;
    grossDemand: number;
    onHandUsableSupply: number;
    horizonEndSurvivingSupply: number;
    usableSupply: number;
    safetyStock: number;
    safetyStockSource: string;
    shortage: number;
    timePhasedShortfall: number;
    expiryRiskSurplus: number;
    demandEvidenceComplete: boolean;
    contributingOrderIds: string[];
    contributingRecipeVersionIds: string[];
    orderContributions: OrderDemandEvidence[];
    contributingLots: LotEvidence[];
    excludedLots: ExcludedLotEvidence[];
}

export interface OrderDemandEvidence {
    orderId: string;
    lineNumber: number;
    dishId: string;
    recipeVersionId: string;
    recipePinProvenance: string;
    recipeYieldProvenance: string;
    orderedQuantity: number;
    preparedQuantity: number;
    remainingQuantity: number;
    orderedAt: string;
    requiredAt: string;
    recipeIngredientQuantity: number;
    recipeIngredientUnit: string;
    recipeYieldQuantity: number;
    recipeYieldUnit: string;
    canonicalDemand: number;
    canonicalUnit: string;
}

export interface LotEvidence {
    lotId: string;
    storedQuantity: number;
    storedUnit: string;
    canonicalQuantity: number;
    canonicalUnit: string;
    purchasedAt: string;
    expiresAt: string;
    expiryProvenance: string;
    allocatedDemand: number;
    remainingAfterDemand: number;
    expiryRiskQuantity: number;
}

export interface UnresolvedOrderEvidence {
    orderId: string;
    lineNumber: number;
    dishId: string;
    provisionalRecipeVersionId: string;
    recipePinProvenance: string;
    recipeYieldProvenance: string;
    orderedQuantity: number;
    preparedQuantity: number;
    orderedAt: string;
    requiredAt: string;
    potentiallyAffectedIngredientIds: string[];
    resolutionCode: string;
}

export interface ExcludedLotEvidence {
    lotId: string;
    storedQuantity: number;
    storedUnit: string;
    expiresAt: string;
    status: string;
    reason: string;
}

export interface PreparationDemand {
    dishId: string;
    dishName: string;
    recipeVersionId: string;
    quantity: number;
    unit: string;
    contributingOrderIds: string[];
    requiredIngredientIds: string[];
}

export interface RecommendationGeneration {
    calculationId: string;
    calculation: DemandCalculation;
    recommendations: GovernedRecommendation[];
}

export interface RecommendationOutcome {
    id: string;
    recommendationId: string;
    type: RecommendationOutcomeType;
    quantity?: number;
    unit?: string;
    notes?: string;
    sourceReferenceType?: string;
    sourceReferenceId?: string;
    occurredAt: string;
    recordedAt: string;
    recordedBy?: string;
}

export interface RecommendationOutcomeInput {
    type: RecommendationOutcomeType;
    quantity?: number;
    unit?: string;
    sourceReferenceType: string;
    sourceReferenceId: string;
    notes: string;
    occurredAt: string;
}

export interface RecommendationExplanation {
    recommendationId: string;
    recommendationVersion: number;
    calculationId: string;
    authoritativeQuantity: number;
    authoritativeUnit: string;
    content: {
        summary: string;
        drivers: string[];
        caveats: string[];
    };
    provider: string;
    model: string;
    promptVersion: string;
    inputHash: string;
    outcome: 'GENERATED' | 'CACHE_HIT' | 'FALLBACK';
    cached: boolean;
    generatedAt: string;
}
