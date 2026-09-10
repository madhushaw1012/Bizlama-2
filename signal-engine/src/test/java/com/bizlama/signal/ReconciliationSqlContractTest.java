package com.bizlama.signal;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class ReconciliationSqlContractTest {

    @Test
    void duplicateStartFixtureIsReplacedForFactsAndFeatureBucketsAtomically()
            throws Exception {
        String fixture = Files.readString(Path.of(
                "bigquery/reconciliation_duplicate_start_fixture.sql"),
                StandardCharsets.UTF_8);
        String repair = Files.readString(Path.of(
                "bigquery/reconciliation.sql"), StandardCharsets.UTF_8);
        String schema = Files.readString(Path.of(
                "bigquery/schema.sql"), StandardCharsets.UTF_8);

        assertThat(fixture).contains("duplicate_live_facts")
                .contains("duplicate_live_features").contains("UNION ALL");
        assertThat(repair).contains("ASSERT (")
                .contains("COUNT(DISTINCT event_id)")
                .contains("BEGIN TRANSACTION")
                .contains("DELETE FROM `${PROJECT_ID}.${DATASET}.signal_facts`")
                .contains("INSERT INTO `${PROJECT_ID}.${DATASET}.signal_facts`")
                .contains("DELETE FROM `${PROJECT_ID}.${DATASET}.signal_features`")
                .contains("INSERT INTO `${PROJECT_ID}.${DATASET}.signal_features`")
                .contains("COMMIT TRANSACTION");
        assertThat(schema)
                .contains("signal_facts_reconciled_staging")
                .contains("signal_features_reconciled_staging")
                .contains("signal_raw_events_current")
                .contains("signal_facts_current")
                .contains("signal_features_current")
                .contains("observed_at TIMESTAMP NOT NULL")
                .contains("PARTITION BY DATE(observed_at)")
                .contains("PARTITION BY DATE(occurred_at)")
                .contains("PARTITION BY DATE(window_start)");
    }
}
