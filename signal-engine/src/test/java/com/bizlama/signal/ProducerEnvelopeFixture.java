package com.bizlama.signal;

import com.bizlama.signal.EventModels.BrokerEvent;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.Map;

final class ProducerEnvelopeFixture {

    private static final ObjectMapper JSON = new ObjectMapper();

    private ProducerEnvelopeFixture() {
    }

    static String authoritativePurchase() {
        try (InputStream input = ProducerEnvelopeFixture.class.getResourceAsStream(
                "/producer-envelope/inventory-purchased-v1.json")) {
            if (input == null) throw new IllegalStateException("fixture missing");
            return new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)
                    .strip();
        } catch (IOException error) {
            throw new IllegalStateException(error);
        }
    }

    static BrokerEvent broker(String envelope) {
        try {
            JsonNode root = JSON.readTree(envelope);
            Map<String, String> attributes = new LinkedHashMap<>();
            for (String field : new String[]{"eventId", "eventType", "kitchenId",
                    "entityType", "entityId", "occurredAt"}) {
                attributes.put(field, root.path(field).asText());
            }
            attributes.put("schemaVersion", root.path("schemaVersion").asText());
            return new BrokerEvent(envelope, attributes);
        } catch (IOException error) {
            throw new IllegalArgumentException(error);
        }
    }

    static BrokerEvent analytics(String eventId, String eventType,
            String kitchen, String ingredient, String occurredAt,
            String recordedAt, String payloadJson) {
        ObjectNode root = JSON.createObjectNode();
        root.put("eventId", eventId);
        root.put("eventType", eventType);
        root.put("schemaVersion", 1);
        root.put("kitchenId", kitchen);
        root.put("entityType", "ingredient");
        root.put("entityId", ingredient);
        root.put("occurredAt", occurredAt);
        root.put("recordedAt", recordedAt);
        root.putNull("correlationId");
        root.putNull("causationId");
        root.put("deduplicationKey", "test:" + eventId);
        try {
            ObjectNode payload = (ObjectNode) JSON.readTree(payloadJson);
            if (!payload.hasNonNull("locationId")) {
                payload.put("locationId", kitchen + "-location");
            }
            root.put("payloadJson", payload.toString());
        } catch (IOException error) {
            throw new IllegalArgumentException("payloadJson must be an object", error);
        }
        root.put("sourceMetadataJson", "{\"producer\":\"test\"}");
        root.put("state", "IN_FLIGHT");
        root.put("attemptCount", 1);
        root.putNull("nextAttemptAt");
        root.putNull("publishedAt");
        root.putNull("lastError");
        return broker(root.toString());
    }

    static BrokerEvent replay(BrokerEvent broker) {
        return BrokerEvent.replay(broker.envelopeJson());
    }
}
