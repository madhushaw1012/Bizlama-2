package com.bizlama.api.events;

import java.time.Instant;
import java.util.List;

public record ParseKitchenEventResponse(
        String proposalId,
        int version,
        String riskTier,
        Instant expiresAt,
        List<ParsedKitchenEvent> events,
        boolean requiresConfirmation,
        boolean autoApplied
) {
}
