package com.bizlama.api.receipts;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "bizlama.receipts.ai")
public record ReceiptAiProperties(
        boolean enabled,
        String projectId,
        String location,
        String model,
        Duration timeout,
        Duration queueTimeout,
        int maxConcurrent,
        int maxAttempts,
        int maxOutputTokens
) {

    public ReceiptAiProperties {
        projectId = normalized(projectId);
        location = normalized(location);
        model = normalized(model);

        if (enabled && projectId.isEmpty()) {
            throw new IllegalArgumentException(
                    "Receipt Vertex AI requires a GCP project ID"
            );
        }
        if (enabled && location.isEmpty()) {
            throw new IllegalArgumentException(
                    "Receipt Vertex AI requires a location"
            );
        }
        if (enabled && model.isEmpty()) {
            throw new IllegalArgumentException(
                    "Receipt Vertex AI requires a model"
            );
        }
        requirePositive(timeout, "Receipt Vertex AI timeout");
        requirePositive(queueTimeout, "Receipt Vertex AI queue timeout");
        if (maxConcurrent < 1 || maxConcurrent > 20) {
            throw new IllegalArgumentException(
                    "Receipt Vertex AI max concurrency must be between 1 and 20"
            );
        }
        if (maxAttempts < 1 || maxAttempts > 5) {
            throw new IllegalArgumentException(
                    "Receipt Vertex AI attempts must be between 1 and 5"
            );
        }
        if (maxOutputTokens < 256 || maxOutputTokens > 8192) {
            throw new IllegalArgumentException(
                    "Receipt Vertex AI output tokens must be between 256 and 8192"
            );
        }
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim();
    }

    private static void requirePositive(Duration value, String label) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(label + " must be positive");
        }
    }
}
