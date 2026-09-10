package com.bizlama.api.signals;

import java.math.BigDecimal;
import java.time.Instant;

public record SignalProposalView(
        String proposalId,
        int schemaVersion,
        String commandType,
        String signalType,
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
        String status,
        int version,
        Instant receivedAt,
        Instant dismissedAt,
        String dismissedBy,
        String dismissalReason,
        boolean executable,
        String nextAction
) {
}
