package com.bizlama.api.analytics.raw;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Set;
import org.springframework.stereotype.Component;

/** Validates and parses complete, versioned outbox envelopes from Pub/Sub. */
@Component
public class RawEventEnvelopeParser {

    private static final Set<String> OUTBOX_STATES = Set.of(
            "PENDING", "IN_FLIGHT", "PUBLISHED", "DEAD_LETTER");

    private final ObjectMapper objectMapper;

    public RawEventEnvelopeParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public RawEventEnvelope parse(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            throw invalid("Envelope must not be empty");
        }

        String envelopeJson = new String(bytes, StandardCharsets.UTF_8);
        JsonNode root;
        try {
            root = objectMapper.readTree(envelopeJson);
        } catch (JsonProcessingException error) {
            throw new RawEventValidationException(
                    "Envelope is not valid JSON", error);
        }
        if (root == null || !root.isObject()) {
            throw invalid("Envelope must be a JSON object");
        }

        int schemaVersion = requiredNonNegativeInteger(
                root, "schemaVersion");
        if (schemaVersion < 1) {
            throw invalid("schemaVersion must be at least one");
        }

        int attemptCount = requiredNonNegativeInteger(root, "attemptCount");
        String state = requiredText(root, "state");
        if (!OUTBOX_STATES.contains(state)) {
            throw invalid("state is not a recognized outbox state");
        }

        String payloadJson = requiredJsonString(root, "payloadJson");
        String sourceMetadataJson = requiredJsonString(
                root, "sourceMetadataJson");

        return new RawEventEnvelope(
                requiredText(root, "eventId"),
                requiredText(root, "eventType"),
                schemaVersion,
                requiredText(root, "kitchenId"),
                requiredText(root, "entityType"),
                requiredText(root, "entityId"),
                requiredInstant(root, "occurredAt"),
                requiredInstant(root, "recordedAt"),
                nullableText(root, "correlationId"),
                nullableText(root, "causationId"),
                requiredText(root, "deduplicationKey"),
                payloadJson,
                sourceMetadataJson,
                state,
                attemptCount,
                nullableInstant(root, "nextAttemptAt"),
                nullableInstant(root, "publishedAt"),
                nullableText(root, "lastError"),
                envelopeJson);
    }

    private String requiredJsonString(JsonNode root, String field) {
        String value = requiredText(root, field);
        try {
            JsonNode json = objectMapper.readTree(value);
            if (json == null) {
                throw invalid(field + " must contain JSON");
            }
        } catch (JsonProcessingException error) {
            throw new RawEventValidationException(
                    field + " must contain valid JSON", error);
        }
        return value;
    }

    private static String requiredText(JsonNode root, String field) {
        JsonNode value = requiredField(root, field);
        if (!value.isTextual() || value.textValue().isBlank()) {
            throw invalid(field + " must be a non-blank string");
        }
        return value.textValue();
    }

    private static String nullableText(JsonNode root, String field) {
        JsonNode value = requiredField(root, field);
        if (value.isNull()) {
            return null;
        }
        if (!value.isTextual()) {
            throw invalid(field + " must be a string or null");
        }
        return value.textValue();
    }

    private static int requiredNonNegativeInteger(
            JsonNode root,
            String field
    ) {
        JsonNode value = requiredField(root, field);
        if (!value.isIntegralNumber() || !value.canConvertToInt()) {
            throw invalid(field + " must be an integer");
        }
        int result = value.intValue();
        if (result < 0) {
            throw invalid(field + " must not be negative");
        }
        return result;
    }

    private static Instant requiredInstant(JsonNode root, String field) {
        JsonNode value = requiredField(root, field);
        if (!value.isTextual()) {
            throw invalid(field + " must be an ISO-8601 timestamp");
        }
        return instant(field, value.textValue());
    }

    private static Instant nullableInstant(JsonNode root, String field) {
        JsonNode value = requiredField(root, field);
        if (value.isNull()) {
            return null;
        }
        if (!value.isTextual()) {
            throw invalid(field + " must be an ISO-8601 timestamp or null");
        }
        return instant(field, value.textValue());
    }

    private static Instant instant(String field, String value) {
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException error) {
            throw new RawEventValidationException(
                    field + " must be an ISO-8601 UTC instant", error);
        }
    }

    private static JsonNode requiredField(JsonNode root, String field) {
        if (!root.has(field)) {
            throw invalid(field + " must be present");
        }
        return root.get(field);
    }

    private static RawEventValidationException invalid(String message) {
        return new RawEventValidationException(message);
    }
}
