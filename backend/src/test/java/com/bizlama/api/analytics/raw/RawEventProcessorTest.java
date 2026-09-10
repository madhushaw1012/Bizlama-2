package com.bizlama.api.analytics.raw;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RawEventProcessorTest {

    private static final Instant NOW = Instant.parse(
            "2026-09-09T12:05:00Z");

    @Test
    void delegatesToIdempotentBoundaryWithDeliveryMetadata() {
        Map<String, RawEventEnvelope> inserted = new LinkedHashMap<>();
        Map<String, RawEventDelivery> deliveries = new LinkedHashMap<>();
        IdempotentRawEventSink sink = (event, delivery) -> {
            inserted.putIfAbsent(event.eventId(), event);
            deliveries.putIfAbsent(event.eventId(), delivery);
        };
        RawEventProcessor processor = processor(sink);
        byte[] body = RawEventEnvelopeParserTest.envelope(1)
                .getBytes(StandardCharsets.UTF_8);

        processor.process(
                body,
                "message-1",
                Instant.parse("2026-09-09T12:04:00Z"),
                "bizlama-raw-sub",
                Map.of("eventId", "evt-101"),
                null,
                false);
        processor.process(
                body,
                "message-2",
                Instant.parse("2026-09-09T12:04:30Z"),
                "bizlama-raw-sub",
                Map.of("eventId", "evt-101"),
                2,
                false);

        assertThat(inserted).containsOnlyKeys("evt-101");
        assertThat(deliveries.get("evt-101").messageId())
                .isEqualTo("message-1");
        assertThat(deliveries.get("evt-101").ingestedAt()).isEqualTo(NOW);
        assertThat(deliveries.get("evt-101").sourceSubscription())
                .isEqualTo("bizlama-raw-sub");
        assertThat(deliveries.get("evt-101").pubsubAttributes())
                .containsEntry("eventId", "evt-101");
        assertThat(deliveries.get("evt-101").deliveryAttempt()).isNull();
        assertThat(deliveries.get("evt-101").replay()).isFalse();
    }

    @Test
    void rejectsMissingPubSubMetadataBeforeWriting() {
        int[] writes = {0};
        RawEventProcessor processor = processor((event, delivery) -> writes[0]++);

        assertThatThrownBy(() -> processor.process(
                RawEventEnvelopeParserTest.envelope(1)
                        .getBytes(StandardCharsets.UTF_8),
                " ",
                NOW,
                "bizlama-raw-sub",
                Map.of(),
                null,
                false))
                .isInstanceOf(RawEventValidationException.class)
                .hasMessage("Pub/Sub messageId must be present");
        assertThat(writes[0]).isZero();
    }

    private static RawEventProcessor processor(IdempotentRawEventSink sink) {
        return new RawEventProcessor(
                new RawEventEnvelopeParser(
                        new ObjectMapper().findAndRegisterModules()),
                sink,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }
}
