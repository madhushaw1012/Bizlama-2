package com.bizlama.api.explanations;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;

public interface ExplanationProvider {

    Metadata metadata();

    RecommendationExplanation.Content explain(Request request) throws Exception;

    record Metadata(
            String provider,
            String model,
            String promptVersion
    ) {
    }

    record Request(
            Snapshot snapshot,
            String canonicalInput,
            String inputHash
    ) {
    }

    record Snapshot(
            int schemaVersion,
            String recommendationId,
            int recommendationVersion,
            String calculationId,
            String recommendationType,
            String recommendationStatus,
            String riskTier,
            String reasonCode,
            BigDecimal authoritativeQuantity,
            String authoritativeUnit,
            BigDecimal confidence,
            String subjectId,
            String subjectName,
            String calculationMethod,
            Instant horizonStart,
            Instant horizonEnd,
            Instant asOf,
            Map<String, Object> evidence
    ) {
        public Snapshot {
            evidence = Map.copyOf(evidence);
        }
    }
}
