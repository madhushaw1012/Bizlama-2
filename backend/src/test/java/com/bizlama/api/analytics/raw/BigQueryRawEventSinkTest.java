package com.bizlama.api.analytics.raw;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.bizlama.api.outbox.CanonicalJson;
import com.google.cloud.bigquery.BigQuery;
import com.google.cloud.bigquery.InsertAllRequest;
import com.google.cloud.bigquery.InsertAllResponse;
import com.google.cloud.bigquery.TableId;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class BigQueryRawEventSinkTest {

    @Test
    void usesEventIdAsStableInsertIdAndStoresCompleteEnvelope() {
        BigQuery bigQuery = mock(BigQuery.class);
        InsertAllResponse response = mock(InsertAllResponse.class);
        when(bigQuery.insertAll(any(InsertAllRequest.class)))
                .thenReturn(response);
        when(response.hasErrors()).thenReturn(false);
        BigQueryRawEventSink sink = new BigQueryRawEventSink(
                bigQuery,
                properties(),
                canonicalJson());
        RawEventEnvelope event = event();

        sink.insertIfAbsent(
                event,
                new RawEventDelivery(
                        "message-1",
                        Instant.parse("2026-09-09T12:04:00Z"),
                        Instant.parse("2026-09-09T12:05:00Z"),
                        "bizlama-raw-sub",
                        Map.of("eventId", "evt-101", "schemaVersion", "1"),
                        null,
                        false));

        ArgumentCaptor<InsertAllRequest> captor = ArgumentCaptor.forClass(
                InsertAllRequest.class);
        verify(bigQuery).insertAll(captor.capture());
        InsertAllRequest request = captor.getValue();
        assertThat(request.getTable()).isEqualTo(TableId.of(
                "bizlama-project",
                "bizlama_analytics",
                "operational_events_raw"));
        assertThat(request.getRows()).hasSize(1);
        assertThat(request.getRows().getFirst().getId()).isEqualTo("evt-101");
        Map<String, Object> row = request.getRows().getFirst().getContent();
        assertThat(row.get("event_id")).isEqualTo("evt-101");
        assertThat(row.get("schema_version")).isEqualTo(1);
        assertThat(row.get("payload_json"))
                .isEqualTo("{\"quantity\":\"2.50\"}");
        assertThat(row.get("envelope_json")).isEqualTo(event.envelopeJson());
        assertThat(row.get("pubsub_message_id")).isEqualTo("message-1");
        assertThat(row.get("source_subscription"))
                .isEqualTo("bizlama-raw-sub");
        assertThat(row.get("pubsub_attributes"))
                .isEqualTo("{\"eventId\":\"evt-101\",\"schemaVersion\":\"1\"}");
        assertThat(row.get("payload_sha256"))
                .isEqualTo(
                        "de6d2d680294c0526b5850190d5f284f"
                                + "2116e5a1d811f6c68cd0c728242d3bc2");
        assertThat(row.get("delivery_attempt")).isNull();
        assertThat(row.get("is_replay")).isEqualTo(false);
    }

    @Test
    void rejectsBigQueryRowErrorsSoPubSubCanRedeliver() {
        BigQuery bigQuery = mock(BigQuery.class);
        InsertAllResponse response = mock(InsertAllResponse.class);
        when(bigQuery.insertAll(any(InsertAllRequest.class)))
                .thenReturn(response);
        when(response.hasErrors()).thenReturn(true);
        when(response.getInsertErrors()).thenReturn(Map.of(0L, List.of()));
        BigQueryRawEventSink sink = new BigQueryRawEventSink(
                bigQuery,
                properties(),
                canonicalJson());

        assertThatThrownBy(() -> sink.insertIfAbsent(
                event(),
                new RawEventDelivery(
                        "message-1",
                        Instant.EPOCH,
                        Instant.EPOCH,
                        "bizlama-raw-sub",
                        Map.of(),
                        null,
                        false)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("BigQuery rejected raw event evt-101");
    }

    private static RawEventEnvelope event() {
        return new RawEventEnvelopeParser(
                new ObjectMapper().findAndRegisterModules())
                .parse(RawEventEnvelopeParserTest.envelope(1)
                        .getBytes(StandardCharsets.UTF_8));
    }

    private static RawAnalyticsProperties properties() {
        return new RawAnalyticsProperties(
                true,
                "bizlama-project",
                "bizlama-raw-sub",
                "bizlama_analytics",
                "operational_events_raw");
    }

    private static CanonicalJson canonicalJson() {
        return new CanonicalJson(
                new ObjectMapper().findAndRegisterModules());
    }
}
