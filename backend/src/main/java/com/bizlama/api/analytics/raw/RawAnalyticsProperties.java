package com.bizlama.api.analytics.raw;

import java.util.regex.Pattern;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "bizlama.analytics.raw")
public record RawAnalyticsProperties(
        boolean enabled,
        String projectId,
        String subscriptionId,
        String dataset,
        String table
) {

    private static final Pattern BIGQUERY_IDENTIFIER = Pattern.compile(
            "[A-Za-z_][A-Za-z0-9_]*");

    public RawAnalyticsProperties {
        if (table == null || table.isBlank()) {
            table = "operational_events_raw";
        }
    }

    public String requiredProjectId() {
        return required(projectId, "project-id");
    }

    public String requiredSubscriptionId() {
        return required(subscriptionId, "subscription-id");
    }

    public String requiredDataset() {
        String value = required(dataset, "dataset");
        if (!BIGQUERY_IDENTIFIER.matcher(value).matches()) {
            throw invalid("dataset must be a valid BigQuery identifier");
        }
        return value;
    }

    public String requiredTable() {
        String value = required(table, "table");
        if (!BIGQUERY_IDENTIFIER.matcher(value).matches()) {
            throw invalid("table must be a valid BigQuery identifier");
        }
        return value;
    }

    private static String required(String value, String name) {
        if (value == null || value.isBlank()) {
            throw invalid(name + " is required when raw analytics is enabled");
        }
        return value.trim();
    }

    private static IllegalStateException invalid(String detail) {
        return new IllegalStateException("bizlama.analytics.raw." + detail);
    }
}
