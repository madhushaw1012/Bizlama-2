package com.bizlama.api.signals;

import java.util.regex.Pattern;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "bizlama.signals.proposals")
public record SignalProposalProperties(
        boolean enabled,
        String projectId,
        String subscriptionId,
        String expectedTargetQueue,
        int maxMessageBytes
) {

    private static final Pattern PROJECT_ID = Pattern.compile(
            "[a-z][a-z0-9-]{4,28}[a-z0-9]");
    private static final Pattern RESOURCE_ID = Pattern.compile(
            "[A-Za-z][A-Za-z0-9._~+%-]{2,254}");

    public SignalProposalProperties {
        if (maxMessageBytes == 0) {
            maxMessageBytes = 65_536;
        }
        if (maxMessageBytes < 1 || maxMessageBytes > 1_000_000) {
            throw invalid("max-message-bytes must be between 1 and 1000000");
        }
    }

    public String requiredProjectId() {
        String value = required(projectId, "project-id");
        if (!PROJECT_ID.matcher(value).matches()) {
            throw invalid("project-id is invalid");
        }
        return value;
    }

    public String requiredSubscriptionId() {
        String value = required(subscriptionId, "subscription-id");
        if (!RESOURCE_ID.matcher(value).matches()) {
            throw invalid("subscription-id is invalid");
        }
        return value;
    }

    public String requiredExpectedTargetQueue() {
        String value = required(expectedTargetQueue, "expected-target-queue");
        String expectedPrefix = "projects/" + requiredProjectId() + "/topics/";
        if (!value.startsWith(expectedPrefix)
                || !RESOURCE_ID.matcher(value.substring(expectedPrefix.length()))
                        .matches()) {
            throw invalid(
                    "expected-target-queue must be a topic in the configured project");
        }
        return value;
    }

    private static String required(String value, String name) {
        if (value == null || value.isBlank()) {
            throw invalid(name + " is required when proposal ingestion is enabled");
        }
        return value.trim();
    }

    private static IllegalStateException invalid(String detail) {
        return new IllegalStateException(
                "bizlama.signals.proposals." + detail);
    }
}
