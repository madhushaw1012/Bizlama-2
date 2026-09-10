package com.bizlama.api.signals;

import com.bizlama.api.outbox.CanonicalJson;
import com.bizlama.api.signals.SignalProposalService.DeliveryEvidence;
import com.bizlama.api.signals.SignalProposalService.IngestResult;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Cloud-neutral processing boundary; permanent validation failures are audited. */
@Component
@ConditionalOnProperty(
        prefix = "bizlama.signals.proposals",
        name = "enabled",
        havingValue = "true")
public class SignalProposalProcessor {

    private final SignalProposalProperties properties;
    private final SignalProposalParser parser;
    private final SignalProposalService service;
    private final CanonicalJson canonicalJson;
    private final MeterRegistry metrics;

    public SignalProposalProcessor(
            SignalProposalProperties properties,
            SignalProposalParser parser,
            SignalProposalService service,
            CanonicalJson canonicalJson,
            MeterRegistry metrics
    ) {
        this.properties = properties;
        this.parser = parser;
        this.service = service;
        this.canonicalJson = canonicalJson;
        this.metrics = metrics;
    }

    IngestResult process(
            byte[] body,
            String messageId,
            Instant publishTime,
            Map<String, String> attributes,
            Integer deliveryAttempt
    ) {
        byte[] safeBody = body == null ? new byte[0] : body;
        String payloadHash = SignalProposalParser.sha256(safeBody);
        String normalizedMessageId = normalizeMessageId(
                messageId, payloadHash, publishTime);
        Map<String, String> safeAttributes = attributes == null
                ? Map.of()
                : Map.copyOf(attributes);
        DeliveryEvidence delivery = new DeliveryEvidence(
                properties.requiredSubscriptionId(),
                normalizedMessageId,
                publishTime,
                payloadHash,
                Base64.getEncoder().encodeToString(safeBody),
                canonicalJson.write(safeAttributes),
                deliveryAttempt);

        if (messageId == null || messageId.isBlank()) {
            return rejected(
                    delivery,
                    null,
                    "MISSING_MESSAGE_ID",
                    "Pub/Sub messageId must be present.");
        }
        if (messageId.length() > 200) {
            return rejected(
                    delivery,
                    null,
                    "INVALID_MESSAGE_ID",
                    "Pub/Sub messageId exceeds 200 characters.");
        }
        if (deliveryAttempt != null && deliveryAttempt < 1) {
            return rejected(
                    delivery,
                    null,
                    "INVALID_DELIVERY_ATTEMPT",
                    "Delivery attempt must be positive when present.");
        }

        try {
            SignalProposalCommand command = parser.parse(
                    safeBody,
                    properties.requiredExpectedTargetQueue(),
                    properties.maxMessageBytes());
            parser.validateAttributes(safeAttributes, command);
            IngestResult result = service.ingest(command, delivery);
            increment(result);
            return result;
        } catch (SignalProposalValidationException error) {
            return rejected(
                    delivery,
                    error.claimedProposalId(),
                    error.code(),
                    error.getMessage());
        }
    }

    private IngestResult rejected(
            DeliveryEvidence delivery,
            String proposalId,
            String code,
            String detail
    ) {
        IngestResult result = service.recordRejected(
                delivery, proposalId, code, detail);
        increment(result);
        return result;
    }

    private void increment(IngestResult result) {
        Counter.builder("bizlama.signal.proposal.deliveries")
                .description("Governed signal proposal delivery outcomes")
                .tag("outcome", result.outcome().name())
                .tag("reason", result.rejectionCode() == null
                        ? "NONE" : result.rejectionCode())
                .register(metrics)
                .increment();
    }

    private static String normalizeMessageId(
            String messageId,
            String payloadHash,
            Instant publishTime
    ) {
        if (messageId != null && !messageId.isBlank()
                && messageId.length() <= 200) {
            return messageId.trim();
        }
        String time = publishTime == null
                ? "missing-time"
                : Long.toString(publishTime.toEpochMilli());
        return "invalid-" + payloadHash.substring(0, 24) + "-" + time;
    }
}
