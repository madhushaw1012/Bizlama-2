package com.bizlama.signal;

import static org.assertj.core.api.Assertions.assertThat;

import com.bizlama.signal.EventModels.AnalyticsType;
import com.bizlama.signal.EventModels.BrokerEvent;
import com.bizlama.signal.EventModels.Dimension;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.HashMap;
import org.junit.jupiter.api.Test;

class EventParserTest {

    private final EventParser parser = new EventParser();

    @Test
    void consumesExactAuthoritativeProducerEnvelopeAndAttributes() {
        BrokerEvent broker = ProducerEnvelopeFixture.broker(
                ProducerEnvelopeFixture.authoritativePurchase());

        var parsed = parser.parse(broker, Duration.ofHours(2), true);

        assertThat(parsed.process()).isTrue();
        assertThat(parsed.retainRaw()).isTrue();
        assertThat(parsed.event().analyticsType())
                .isEqualTo(AnalyticsType.INVENTORY_PURCHASED);
        assertThat(parsed.event().locationId()).isEqualTo("location-a");
        assertThat(parsed.event().entityType()).isEqualTo("stock_lot");
        assertThat(parsed.event().entityId()).isEqualTo("lot-1");
        assertThat(parsed.event().correlationId()).isEqualTo("receipt-1");
        assertThat(parsed.event().deduplicationKey())
                .isEqualTo("stock-purchase:lot-1");
        assertThat(parsed.event().sourceQuantity())
                .isEqualByComparingTo(new BigDecimal("2.500"));
        assertThat(parsed.event().canonicalQuantity())
                .isEqualByComparingTo(new BigDecimal("2500"));
        assertThat(parsed.event().canonicalUnit()).isEqualTo("g");
        assertThat(parsed.event().dimension()).isEqualTo(Dimension.MASS);
    }

    @Test
    void validUnsupportedDomainEventIsImmutableRawOnly() {
        BrokerEvent event = ProducerEnvelopeFixture.analytics(
                "accepted-1", "ORDER_ACCEPTED", "kitchen-a", "order-1",
                "2026-09-09T10:00:00Z", "2026-09-09T10:00:01Z",
                "{\"total\":\"14.25\"}");

        var parsed = parser.parse(event, Duration.ofHours(2), true);

        assertThat(parsed.retainRaw()).isTrue();
        assertThat(parsed.process()).isFalse();
        assertThat(parsed.error()).isNull();
        assertThat(parsed.event().analyticsType()).isEqualTo(AnalyticsType.RAW_ONLY);
    }

    @Test
    void producerDelayIsNotMisclassifiedAsBeamLateness() {
        BrokerEvent delayed = ProducerEnvelopeFixture.analytics(
                "delayed-1", "ORDER_INGREDIENT_DEMAND", "kitchen-a", "rice",
                "2026-09-09T10:00:00Z", "2026-09-09T14:00:00Z",
                "{\"ingredientId\":\"rice\",\"canonicalDemand\":1.25,"
                        + "\"canonicalUnit\":\"kg\"}");

        var parsed = parser.parse(delayed, Duration.ofHours(2), true);

        assertThat(parsed.process()).isTrue();
        assertThat(parsed.error()).isNull();
        assertThat(parsed.event().recordedAtMillis()
                - parsed.event().occurredAtMillis()).isEqualTo(14_400_000L);
    }

    @Test
    void everyTamperedOrMissingBrokerAttributeQuarantinesBodyBeforeFacts() {
        BrokerEvent valid = ProducerEnvelopeFixture.broker(
                ProducerEnvelopeFixture.authoritativePurchase());
        for (String attribute : valid.attributes().keySet()) {
            var tampered = new HashMap<>(valid.attributes());
            tampered.put(attribute, "tampered");
            assertAttributeMismatch(valid, tampered);
        }
        var missing = new HashMap<>(valid.attributes());
        missing.remove("occurredAt");
        assertAttributeMismatch(valid, missing);
    }

    private void assertAttributeMismatch(
            BrokerEvent valid,
            HashMap<String, String> attributes
    ) {
        var parsed = parser.parse(new BrokerEvent(valid.envelopeJson(), attributes),
                Duration.ofHours(2), true);
        assertThat(parsed.retainRaw()).isTrue();
        assertThat(parsed.process()).isFalse();
        assertThat(parsed.error().reasonCode())
                .isEqualTo("BROKER_ATTRIBUTE_MISMATCH");
        assertThat(parsed.error().originalEventId()).isEqualTo("producer-event-1");
    }

    @Test
    void invalidSupportedPayloadIsRetainedRawAndQuarantined() throws Exception {
        ObjectNode root = (ObjectNode) new ObjectMapper().readTree(
                ProducerEnvelopeFixture.authoritativePurchase());
        root.put("payloadJson", "{\"ingredientId\":\"rice\",\"unit\":\"g\"}");
        BrokerEvent event = ProducerEnvelopeFixture.broker(root.toString());

        var parsed = parser.parse(event, Duration.ofHours(2), true);

        assertThat(parsed.retainRaw()).isTrue();
        assertThat(parsed.process()).isFalse();
        assertThat(parsed.error().reasonCode())
                .isEqualTo("INVALID_ANALYTICS_PAYLOAD");
    }

    @Test
    void malformedEnvelopeAndEmbeddedJsonAreStructurallyInvalid() throws Exception {
        var malformed = parser.parse("{\"eventId\":\"broken-17\", no-json",
                Duration.ofHours(2));
        ObjectNode root = (ObjectNode) new ObjectMapper().readTree(
                ProducerEnvelopeFixture.authoritativePurchase());
        root.put("payloadJson", "not-json");
        var embedded = parser.parse(root.toString(), Duration.ofHours(2));

        assertThat(malformed.retainRaw()).isFalse();
        assertThat(malformed.error().originalEventId()).isEqualTo("broken-17");
        assertThat(embedded.retainRaw()).isFalse();
        assertThat(embedded.error().reasonCode()).isEqualTo("INVALID_ENVELOPE");
    }
}
