package com.bizlama.api.explanations;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "bizlama.explanations.vertex")
public record VertexExplanationProperties(
        @DefaultValue("false") boolean enabled,
        @DefaultValue("") String projectId,
        @DefaultValue("global") String location,
        @DefaultValue("gemini-2.5-flash") String model,
        @DefaultValue("PT8S") Duration timeout,
        @DefaultValue("PT0.25S") Duration queueTimeout,
        @DefaultValue("2") int maxConcurrent,
        @DefaultValue("2") int maxAttempts,
        @DefaultValue("512") int maxOutputTokens
) {
    public VertexExplanationProperties {
        requirePositive(timeout, "timeout");
        requirePositive(queueTimeout, "queue-timeout");
        if (maxConcurrent < 1 || maxConcurrent > 16) {
            throw new IllegalArgumentException(
                    "bizlama.explanations.vertex.max-concurrent must be from 1 to 16"
            );
        }
        if (maxAttempts < 1 || maxAttempts > 3) {
            throw new IllegalArgumentException(
                    "bizlama.explanations.vertex.max-attempts must be from 1 to 3"
            );
        }
        if (maxOutputTokens < 64 || maxOutputTokens > 2048) {
            throw new IllegalArgumentException(
                    "bizlama.explanations.vertex.max-output-tokens must be from 64 to 2048"
            );
        }
        if (enabled) {
            requireText(projectId, "project-id");
            requireText(location, "location");
            requireText(model, "model");
        }
    }

    private static void requirePositive(Duration value, String name) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(
                    "bizlama.explanations.vertex." + name + " must be positive"
            );
        }
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(
                    "bizlama.explanations.vertex." + name + " is required when enabled"
            );
        }
    }
}
