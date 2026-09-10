package com.bizlama.api.analytics.raw;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.bizlama.api.outbox.CanonicalJson;
import com.bizlama.api.outbox.OutboxEvent;
import com.bizlama.api.outbox.OutboxState;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class RawEventEnvelopeParserTest {

    private final RawEventEnvelopeParser parser = new RawEventEnvelopeParser(
            new ObjectMapper().findAndRegisterModules());

    @Test
    void parsesCompleteEnvelopeAndAcceptsFuturePositiveSchemaVersion() {
        RawEventEnvelope event = parser.parse(envelope(7).getBytes(
                StandardCharsets.UTF_8));

        assertThat(event.eventId()).isEqualTo("evt-101");
        assertThat(event.schemaVersion()).isEqualTo(7);
        assertThat(event.correlationId()).isNull();
        assertThat(event.payloadJson()).isEqualTo("{\"quantity\":\"2.50\"}");
        assertThat(event.envelopeJson()).isEqualTo(envelope(7));
    }

    @Test
    void consumesTheExactCanonicalOutboxEventWireFormat() {
        ObjectMapper mapper = new ObjectMapper()
                .findAndRegisterModules()
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        OutboxEvent outboxEvent = new OutboxEvent(
                "evt-wire",
                "ORDER_ACCEPTED",
                1,
                "KITCHEN-001",
                "order",
                "ORDER-001",
                java.time.Instant.parse("2026-09-09T12:00:00Z"),
                java.time.Instant.parse("2026-09-09T12:00:01Z"),
                null,
                null,
                "order:ORDER-001:accepted",
                "{\"total\":\"14.25\"}",
                "{\"producer\":\"operational-api\"}",
                OutboxState.IN_FLIGHT,
                2,
                java.time.Instant.parse("2026-09-09T12:01:00Z"),
                null,
                null);

        String wireJson = new CanonicalJson(mapper).write(outboxEvent);
        RawEventEnvelope parsed = parser.parse(
                wireJson.getBytes(StandardCharsets.UTF_8));

        assertThat(parsed.eventId()).isEqualTo("evt-wire");
        assertThat(parsed.state()).isEqualTo("IN_FLIGHT");
        assertThat(parsed.envelopeJson()).isEqualTo(wireJson);
    }

    @Test
    void requiresNullableMembersToBeExplicitlyPresent() {
        String missingPublishedAt = envelope(1).replace(
                "\"publishedAt\":null,", "");

        assertThatThrownBy(() -> parser.parse(
                missingPublishedAt.getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(RawEventValidationException.class)
                .hasMessage("publishedAt must be present");
    }

    @Test
    void rejectsNonPositiveSchemaVersion() {
        assertThatThrownBy(() -> parser.parse(
                envelope(0).getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(RawEventValidationException.class)
                .hasMessage("schemaVersion must be at least one");
    }

    @Test
    void rejectsInvalidEmbeddedJson() {
        String invalidPayload = envelope(1).replace(
                "{\\\"quantity\\\":\\\"2.50\\\"}",
                "not-json");

        assertThatThrownBy(() -> parser.parse(
                invalidPayload.getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(RawEventValidationException.class)
                .hasMessage("payloadJson must contain valid JSON");
    }

    static String envelope(int schemaVersion) {
        return """
                {"eventId":"evt-101","eventType":"INVENTORY_PURCHASED",\
                "schemaVersion":%d,"kitchenId":"KITCHEN-001",\
                "entityType":"inventory_lot","entityId":"LOT-101",\
                "occurredAt":"2026-09-09T12:00:00Z",\
                "recordedAt":"2026-09-09T12:00:01Z",\
                "correlationId":null,"causationId":"receipt-1",\
                "deduplicationKey":"purchase:LOT-101",\
                "payloadJson":"{\\"quantity\\":\\"2.50\\"}",\
                "sourceMetadataJson":"{\\"source\\":\\"receipt\\"}",\
                "state":"IN_FLIGHT","attemptCount":1,\
                "nextAttemptAt":"2026-09-09T12:01:00Z",\
                "publishedAt":null,"lastError":null}
                """.formatted(schemaVersion).strip();
    }
}
