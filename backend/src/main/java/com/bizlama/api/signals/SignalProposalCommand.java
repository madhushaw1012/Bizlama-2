package com.bizlama.api.signals;

import java.math.BigDecimal;
import java.time.Instant;

record SignalProposalCommand(
        String proposalId,
        int schemaVersion,
        String commandType,
        SignalType signalType,
        String riskTier,
        String kitchenId,
        String locationId,
        String ingredientId,
        String windowName,
        Instant windowStart,
        Instant windowEnd,
        BigDecimal suggestedQuantity,
        String canonicalUnit,
        String reasonCode,
        String evidenceJson,
        String targetQueue,
        boolean directMutationAllowed,
        String commandSha256
) {
    enum SignalType {
        SHORTAGE_RISK,
        EXPIRY_RISK_SURPLUS,
        MATERIAL_DATA_QUALITY
    }
}
