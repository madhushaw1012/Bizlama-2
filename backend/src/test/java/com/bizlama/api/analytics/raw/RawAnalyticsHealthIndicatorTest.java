package com.bizlama.api.analytics.raw;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.google.cloud.bigquery.BigQuery;
import com.google.cloud.bigquery.Table;
import com.google.cloud.bigquery.TableId;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.actuate.health.Status;

class RawAnalyticsHealthIndicatorTest {

    @Test
    void disabledWorkerIsHealthyWithoutCloudCalls() {
        RawAnalyticsProperties properties = properties(false);
        ObjectProvider<RawPubSubSubscriberWorker> workers = provider();
        ObjectProvider<BigQuery> bigQueries = provider();
        RawAnalyticsHealthIndicator indicator = new RawAnalyticsHealthIndicator(
                properties, workers, bigQueries);

        assertThat(indicator.health().getStatus()).isEqualTo(Status.UP);
        verifyNoInteractions(workers, bigQueries);
    }

    @Test
    void enabledWorkerRequiresRunningSubscriberAndExistingBigQueryTable() {
        RawAnalyticsProperties properties = properties(true);
        RawPubSubSubscriberWorker worker =
                mock(RawPubSubSubscriberWorker.class);
        BigQuery bigQuery = mock(BigQuery.class);
        ObjectProvider<RawPubSubSubscriberWorker> workers = provider();
        ObjectProvider<BigQuery> bigQueries = provider();
        when(workers.getIfAvailable()).thenReturn(worker);
        when(bigQueries.getIfAvailable()).thenReturn(bigQuery);
        RawAnalyticsHealthIndicator indicator = new RawAnalyticsHealthIndicator(
                properties, workers, bigQueries);

        assertThat(indicator.health().getStatus()).isEqualTo(Status.DOWN);

        when(worker.isRunning()).thenReturn(true);
        assertThat(indicator.health().getStatus()).isEqualTo(Status.DOWN);

        when(bigQuery.getTable(TableId.of(
                "project-demo", "analytics", "operational_events_raw")))
                .thenReturn(mock(Table.class));
        assertThat(indicator.health().getStatus()).isEqualTo(Status.UP);
    }

    private static RawAnalyticsProperties properties(boolean enabled) {
        return new RawAnalyticsProperties(
                enabled,
                "project-demo",
                "subscription-demo",
                "analytics",
                "operational_events_raw");
    }

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> provider() {
        return mock(ObjectProvider.class);
    }
}
