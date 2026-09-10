package com.bizlama.api.explanations;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

public record RecommendationExplanation(
        String recommendationId,
        int recommendationVersion,
        String calculationId,
        BigDecimal authoritativeQuantity,
        String authoritativeUnit,
        Content content,
        String provider,
        String model,
        String promptVersion,
        String inputHash,
        Outcome outcome,
        boolean cached,
        Instant generatedAt
) {
    public RecommendationExplanation {
        Objects.requireNonNull(content, "content");
    }

    public enum Outcome {
        GENERATED,
        CACHE_HIT,
        FALLBACK
    }

    public record Content(
            String summary,
            List<String> drivers,
            List<String> caveats
    ) {
        public Content {
            drivers = List.copyOf(drivers);
            caveats = List.copyOf(caveats);
        }
    }
}
