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
        boolean autoApplied,
        KitchenEventIntent intent,
        double confidence,
        String clarification
) {

    public ParseKitchenEventResponse(
            String proposalId,
            int version,
            String riskTier,
            Instant expiresAt,
            List<ParsedKitchenEvent> events,
            boolean requiresConfirmation,
            boolean autoApplied
    ) {
        this(
                proposalId,
                version,
                riskTier,
                expiresAt,
                events,
                requiresConfirmation,
                autoApplied,
                events == null || events.isEmpty()
                        ? KitchenEventIntent.UNKNOWN
                        : events.getFirst().intent(),
                events == null || events.isEmpty()
                        ? 0.0
                        : events.stream()
                                .mapToDouble(ParsedKitchenEvent::confidence)
                                .min()
                                .orElse(0.0),
                null
        );
    }

    public static ParseKitchenEventResponse unknown(String clarification) {
        return new ParseKitchenEventResponse(
                null, 0, "HIGH", null, List.of(), false, false,
                KitchenEventIntent.UNKNOWN, 0.0, clarification
        );
    }
}
