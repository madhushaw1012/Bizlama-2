package com.bizlama.signal;

import static org.assertj.core.api.Assertions.assertThat;

import com.bizlama.signal.EventModels.ErrorRecord;
import com.google.api.services.bigquery.model.TableRow;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class BigQueryMappingsTest {

    @Test
    void errorRowsUseObservationTimeWhenMalformedInputHasNoEventTime() {
        long observedAt = Instant.parse("2026-09-09T12:34:56Z").toEpochMilli();
        ErrorRecord error = new ErrorRecord(
                "MALFORMED_ENVELOPE",
                "Envelope is not valid JSON.",
                "broken-17",
                "{not-json",
                0,
                observedAt
        );

        TableRow row = BigQueryMappings.errorRow(error);

        assertThat(row.get("event_time")).isNull();
        assertThat(row.get("observed_at")).isEqualTo("2026-09-09T12:34:56Z");
        assertThat(BigQueryMappings.errorSchema().getFields())
                .anySatisfy(field -> {
                    assertThat(field.getName()).isEqualTo("observed_at");
                    assertThat(field.getType()).isEqualTo("TIMESTAMP");
                    assertThat(field.getMode()).isEqualTo("REQUIRED");
                });
    }
}
