package com.bizlama.api.analytics.raw;

import com.google.cloud.bigquery.BigQuery;
import com.google.cloud.bigquery.Table;
import com.google.cloud.bigquery.TableId;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * Readiness contribution for the separately enabled raw-retention worker.
 *
 * <p>When enabled, this checks both the live Pub/Sub subscriber lifecycle and
 * BigQuery table metadata. The API process, where raw retention is disabled,
 * reports that state without attempting cloud calls.</p>
 */
@Component("rawAnalytics")
public class RawAnalyticsHealthIndicator implements HealthIndicator {

    private final RawAnalyticsProperties properties;
    private final ObjectProvider<RawPubSubSubscriberWorker> workers;
    private final ObjectProvider<BigQuery> bigQueries;

    public RawAnalyticsHealthIndicator(
            RawAnalyticsProperties properties,
            ObjectProvider<RawPubSubSubscriberWorker> workers,
            ObjectProvider<BigQuery> bigQueries
    ) {
        this.properties = properties;
        this.workers = workers;
        this.bigQueries = bigQueries;
    }

    @Override
    public Health health() {
        if (!properties.enabled()) {
            return Health.up()
                    .withDetail("enabled", false)
                    .build();
        }

        RawPubSubSubscriberWorker worker = workers.getIfAvailable();
        BigQuery bigQuery = bigQueries.getIfAvailable();
        if (worker == null || bigQuery == null) {
            return Health.down()
                    .withDetail("enabled", true)
                    .withDetail("configuration", "enabled worker beans missing")
                    .build();
        }
        if (!worker.isRunning()) {
            return Health.down()
                    .withDetail("enabled", true)
                    .withDetail("subscriber", "not running")
                    .build();
        }

        try {
            TableId tableId = TableId.of(
                    properties.requiredProjectId(),
                    properties.requiredDataset(),
                    properties.requiredTable());
            Table table = bigQuery.getTable(tableId);
            if (table == null) {
                return Health.down()
                        .withDetail("enabled", true)
                        .withDetail("subscriber", "running")
                        .withDetail("bigQueryTable", "missing")
                        .build();
            }
            return Health.up()
                    .withDetail("enabled", true)
                    .withDetail("subscriber", "running")
                    .withDetail(
                            "bigQueryTable",
                            properties.requiredDataset()
                                    + "." + properties.requiredTable())
                    .build();
        } catch (RuntimeException failure) {
            return Health.down(failure)
                    .withDetail("enabled", true)
                    .withDetail("subscriber", "running")
                    .build();
        }
    }
}
