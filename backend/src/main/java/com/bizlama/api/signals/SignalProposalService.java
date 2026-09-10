package com.bizlama.api.signals;

import com.bizlama.api.config.JdbcTimestamp;
import com.bizlama.api.config.WorkspaceProperties;
import com.bizlama.api.outbox.OutboxEventDraft;
import com.bizlama.api.outbox.TransactionalOutboxService;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Durable boundary between advisory signal output and operational state. */
@Service
public class SignalProposalService {

    private static final int MAX_REJECTION_DETAIL = 1000;

    private final JdbcClient jdbc;
    private final WorkspaceProperties workspace;
    private final TransactionalOutboxService outbox;
    private final Clock clock;

    public SignalProposalService(
            JdbcClient jdbc,
            WorkspaceProperties workspace,
            TransactionalOutboxService outbox,
            Clock clock
    ) {
        this.jdbc = jdbc;
        this.workspace = workspace;
        this.outbox = outbox;
        this.clock = clock;
    }

    @Transactional
    IngestResult ingest(
            SignalProposalCommand command,
            DeliveryEvidence delivery
    ) {
        Optional<StoredDelivery> exactDelivery = exactDelivery(delivery);
        if (exactDelivery.isPresent()) {
            StoredDelivery stored = exactDelivery.get();
            IngestOutcome replayOutcome = "REJECTED".equals(stored.outcome())
                    ? IngestOutcome.REJECTED
                    : IngestOutcome.DUPLICATE;
            return new IngestResult(
                    replayOutcome,
                    stored.rejectionCode(),
                    stored.claimedProposalId());
        }
        if (messageIdHasDifferentBody(delivery)) {
            return reject(
                    delivery,
                    command.proposalId(),
                    "MESSAGE_ID_COLLISION",
                    "The Pub/Sub message ID was already observed with a different body.");
        }
        if (delivery.publishTime() == null) {
            return reject(
                    delivery,
                    command.proposalId(),
                    "MISSING_PUBLISH_TIME",
                    "Pub/Sub publishTime must be present.");
        }

        Scope scope = lockOperationalScope(command);
        if (scope.rejectionCode() != null) {
            return reject(
                    delivery,
                    command.proposalId(),
                    scope.rejectionCode(),
                    scope.rejectionDetail());
        }
        if (command.canonicalUnit() != null
                && !scope.baseUnit().equals(command.canonicalUnit())) {
            return reject(
                    delivery,
                    command.proposalId(),
                    "CANONICAL_UNIT_MISMATCH",
                    "Signal unit does not match the active ingredient base unit.");
        }

        Optional<StoredProposal> existing = storedProposal(command.proposalId());
        if (existing.isPresent()) {
            if (!existing.get().commandSha256().equals(command.commandSha256())) {
                return reject(
                        delivery,
                        command.proposalId(),
                        "PROPOSAL_ID_COLLISION",
                        "The proposal ID was already stored with different immutable content.");
            }
            insertDelivery(
                    delivery,
                    command.proposalId(),
                    IngestOutcome.DUPLICATE,
                    null,
                    null);
            return new IngestResult(
                    IngestOutcome.DUPLICATE, null, command.proposalId());
        }

        Instant receivedAt = clock.instant();
        jdbc.sql("""
                        INSERT INTO governed_signal_proposals
                        (proposal_id, schema_version, command_type, signal_type,
                         risk_tier, kitchen_id, location_id, ingredient_id,
                         window_name, window_start, window_end,
                         suggested_quantity, canonical_unit, reason_code,
                         evidence_json, target_queue, direct_mutation_allowed,
                         command_sha256, status, version, received_at,
                         first_message_id, first_publish_time)
                        VALUES
                        (:proposalId, :schemaVersion, :commandType, :signalType,
                         :riskTier, :kitchen, :location, :ingredient,
                         :windowName, :windowStart, :windowEnd,
                         :suggestedQuantity, :canonicalUnit, :reasonCode,
                         :evidenceJson, :targetQueue, FALSE,
                         :commandSha256, 'PENDING', 1, :receivedAt,
                         :messageId, :publishTime)
                        """)
                .param("proposalId", command.proposalId())
                .param("schemaVersion", command.schemaVersion())
                .param("commandType", command.commandType())
                .param("signalType", command.signalType().name())
                .param("riskTier", command.riskTier())
                .param("kitchen", command.kitchenId())
                .param("location", command.locationId())
                .param("ingredient", command.ingredientId())
                .param("windowName", command.windowName())
                .param("windowStart", JdbcTimestamp.utc(command.windowStart()))
                .param("windowEnd", JdbcTimestamp.utc(command.windowEnd()))
                .param("suggestedQuantity", command.suggestedQuantity())
                .param("canonicalUnit", command.canonicalUnit())
                .param("reasonCode", command.reasonCode())
                .param("evidenceJson", command.evidenceJson())
                .param("targetQueue", command.targetQueue())
                .param("commandSha256", command.commandSha256())
                .param("receivedAt", JdbcTimestamp.utc(receivedAt))
                .param("messageId", delivery.messageId())
                .param("publishTime", JdbcTimestamp.utc(delivery.publishTime()))
                .update();
        insertDelivery(
                delivery,
                command.proposalId(),
                IngestOutcome.ACCEPTED,
                null,
                null);
        appendReceivedEvent(command, receivedAt);
        return new IngestResult(
                IngestOutcome.ACCEPTED, null, command.proposalId());
    }

    @Transactional
    IngestResult recordRejected(
            DeliveryEvidence delivery,
            String claimedProposalId,
            String code,
            String detail
    ) {
        Optional<StoredDelivery> exactDelivery = exactDelivery(delivery);
        if (exactDelivery.isPresent()) {
            StoredDelivery stored = exactDelivery.get();
            IngestOutcome replayOutcome = "REJECTED".equals(stored.outcome())
                    ? IngestOutcome.REJECTED
                    : IngestOutcome.DUPLICATE;
            return new IngestResult(
                    replayOutcome,
                    stored.rejectionCode(),
                    stored.claimedProposalId());
        }
        if (messageIdHasDifferentBody(delivery)) {
            code = "MESSAGE_ID_COLLISION";
            detail = "The Pub/Sub message ID was already observed with a different body.";
        }
        return reject(delivery, claimedProposalId, code, detail);
    }

    public List<SignalProposalView> list(
            String kitchenId,
            String locationId,
            String status,
            int limit
    ) {
        requireConfiguredScope(kitchenId, locationId);
        if (limit < 1 || limit > 200) {
            throw new SignalProposalRequestException(
                    "limit must be between 1 and 200.");
        }
        if (status != null
                && !"PENDING".equals(status)
                && !"DISMISSED".equals(status)) {
            throw new SignalProposalRequestException(
                    "status must be PENDING or DISMISSED.");
        }
        String filter = status == null ? "" : " AND status = :status ";
        JdbcClient.StatementSpec query = jdbc.sql(PROPOSAL_SELECT + """
                        WHERE kitchen_id = :kitchen
                          AND location_id = :location
                        """ + filter + """
                        ORDER BY received_at DESC, proposal_id
                        LIMIT :limit
                        """)
                .param("kitchen", kitchenId)
                .param("location", locationId)
                .param("limit", limit);
        if (status != null) {
            query = query.param("status", status);
        }
        return query.query(SignalProposalService::mapProposal).list();
    }

    public SignalProposalView get(String proposalId) {
        return jdbc.sql(PROPOSAL_SELECT
                        + " WHERE proposal_id = :id"
                        + " AND kitchen_id = :kitchen"
                        + " AND location_id = :location")
                .param("id", proposalId)
                .param("kitchen", workspace.kitchenId())
                .param("location", workspace.locationId())
                .query(SignalProposalService::mapProposal)
                .optional()
                .orElseThrow(() -> new SignalProposalNotFoundException(
                        "Signal proposal not found."));
    }

    @Transactional
    public SignalProposalView dismiss(
            String proposalId,
            String kitchenId,
            String locationId,
            int expectedVersion,
            String actor,
            String reason
    ) {
        requireConfiguredScope(kitchenId, locationId);
        String normalizedActor = required(actor, 200, "actor");
        String normalizedReason = required(reason, 500, "reason");
        SignalProposalView existing = jdbc.sql(
                        PROPOSAL_SELECT + """
                                WHERE proposal_id = :id
                                  AND kitchen_id = :kitchen
                                  AND location_id = :location
                                FOR UPDATE
                                """)
                .param("id", proposalId)
                .param("kitchen", kitchenId)
                .param("location", locationId)
                .query(SignalProposalService::mapProposal)
                .optional()
                .orElseThrow(() -> new SignalProposalNotFoundException(
                        "Signal proposal not found in this workspace."));
        if (!"PENDING".equals(existing.status())
                || existing.version() != expectedVersion) {
            throw new SignalProposalConflictException(
                    "Only the current pending signal proposal can be dismissed.");
        }
        Instant now = clock.instant();
        int changed = jdbc.sql("""
                        UPDATE governed_signal_proposals
                        SET status = 'DISMISSED',
                            version = version + 1,
                            dismissed_at = :dismissedAt,
                            dismissed_by = :actor,
                            dismissal_reason = :reason
                        WHERE proposal_id = :id
                          AND kitchen_id = :kitchen
                          AND location_id = :location
                          AND status = 'PENDING'
                          AND version = :expectedVersion
                        """)
                .param("dismissedAt", JdbcTimestamp.utc(now))
                .param("actor", normalizedActor)
                .param("reason", normalizedReason)
                .param("id", proposalId)
                .param("kitchen", kitchenId)
                .param("location", locationId)
                .param("expectedVersion", expectedVersion)
                .update();
        if (changed != 1) {
            throw new SignalProposalConflictException(
                    "Signal proposal changed before it could be dismissed.");
        }
        outbox.append(new OutboxEventDraft(
                null,
                "GOVERNED_SIGNAL_PROPOSAL_DISMISSED",
                1,
                kitchenId,
                "governed_signal_proposal",
                proposalId,
                now,
                proposalId,
                null,
                "signal-proposal-dismissed:" + proposalId,
                Map.of(
                        "proposalId", proposalId,
                        "locationId", locationId,
                        "status", "DISMISSED",
                        "actor", normalizedActor,
                        "reason", normalizedReason,
                        "executable", false),
                Map.of(
                        "producer", "bizlama-api",
                        "authority", "operator-governance")));
        return get(proposalId);
    }

    private Scope lockOperationalScope(SignalProposalCommand command) {
        if (!workspace.kitchenId().equals(command.kitchenId())
                || !workspace.locationId().equals(command.locationId())) {
            return Scope.rejected(
                    "OUT_OF_SCOPE",
                    "Signal kitchen/location is outside this deployed workspace.");
        }
        Optional<String> baseUnit = jdbc.sql("""
                        SELECT ingredient.base_unit
                        FROM kitchens kitchen
                        JOIN kitchen_locations location
                          ON location.kitchen_id = kitchen.id
                         AND location.id = :location
                        JOIN ingredients ingredient
                          ON ingredient.kitchen_id = kitchen.id
                         AND ingredient.id = :ingredient
                        WHERE kitchen.id = :kitchen
                          AND kitchen.active = TRUE
                          AND location.active = TRUE
                          AND ingredient.active = TRUE
                        FOR UPDATE
                        """)
                .param("kitchen", command.kitchenId())
                .param("location", command.locationId())
                .param("ingredient", command.ingredientId())
                .query(String.class)
                .optional();
        return baseUnit
                .map(Scope::accepted)
                .orElseGet(() -> Scope.rejected(
                        "OUT_OF_SCOPE_OR_INACTIVE",
                        "Signal kitchen, location, or ingredient is inactive or out of scope."));
    }

    private IngestResult reject(
            DeliveryEvidence delivery,
            String claimedProposalId,
            String code,
            String detail
    ) {
        String safeCode = required(code, 80, "rejection code");
        String safeDetail = required(detail, MAX_REJECTION_DETAIL, "rejection detail");
        String safeClaimedId = claimedProposalId != null
                && claimedProposalId.length() <= 100
                ? claimedProposalId
                : null;
        insertDelivery(
                delivery,
                safeClaimedId,
                IngestOutcome.REJECTED,
                safeCode,
                safeDetail);
        return new IngestResult(
                IngestOutcome.REJECTED, safeCode, safeClaimedId);
    }

    private void insertDelivery(
            DeliveryEvidence delivery,
            String claimedProposalId,
            IngestOutcome outcome,
            String rejectionCode,
            String rejectionDetail
    ) {
        jdbc.sql("""
                        INSERT INTO signal_proposal_deliveries
                        (delivery_id, source_subscription, pubsub_message_id,
                         pubsub_publish_time, claimed_proposal_id,
                         payload_sha256, payload_base64, attributes_json,
                         delivery_attempt, outcome, rejection_code,
                         rejection_detail, received_at)
                        VALUES
                        (:id, :subscription, :messageId, :publishTime,
                         :proposalId, :payloadSha256, :payloadBase64,
                         :attributesJson, :deliveryAttempt, :outcome,
                         :rejectionCode, :rejectionDetail, :receivedAt)
                        """)
                .param("id", UUID.randomUUID().toString())
                .param("subscription", delivery.sourceSubscription())
                .param("messageId", delivery.messageId())
                .param("publishTime", delivery.publishTime() == null
                        ? null : JdbcTimestamp.utc(delivery.publishTime()))
                .param("proposalId", claimedProposalId)
                .param("payloadSha256", delivery.payloadSha256())
                .param("payloadBase64", delivery.payloadBase64())
                .param("attributesJson", delivery.attributesJson())
                .param("deliveryAttempt", delivery.deliveryAttempt())
                .param("outcome", outcome.name())
                .param("rejectionCode", rejectionCode)
                .param("rejectionDetail", rejectionDetail)
                .param("receivedAt", JdbcTimestamp.utc(clock.instant()))
                .update();
    }

    private Optional<StoredDelivery> exactDelivery(DeliveryEvidence delivery) {
        return jdbc.sql("""
                        SELECT outcome, rejection_code, claimed_proposal_id
                        FROM signal_proposal_deliveries
                        WHERE source_subscription = :subscription
                          AND pubsub_message_id = :messageId
                          AND payload_sha256 = :payloadSha256
                        """)
                .param("subscription", delivery.sourceSubscription())
                .param("messageId", delivery.messageId())
                .param("payloadSha256", delivery.payloadSha256())
                .query((rs, row) -> new StoredDelivery(
                        rs.getString("outcome"),
                        rs.getString("rejection_code"),
                        rs.getString("claimed_proposal_id")))
                .optional();
    }

    private boolean messageIdHasDifferentBody(DeliveryEvidence delivery) {
        return jdbc.sql("""
                        SELECT COUNT(*)
                        FROM signal_proposal_deliveries
                        WHERE source_subscription = :subscription
                          AND pubsub_message_id = :messageId
                          AND payload_sha256 <> :payloadSha256
                        """)
                .param("subscription", delivery.sourceSubscription())
                .param("messageId", delivery.messageId())
                .param("payloadSha256", delivery.payloadSha256())
                .query(Long.class)
                .single() > 0;
    }

    private Optional<StoredProposal> storedProposal(String proposalId) {
        return jdbc.sql("""
                        SELECT command_sha256
                        FROM governed_signal_proposals
                        WHERE proposal_id = :id
                        """)
                .param("id", proposalId)
                .query((rs, row) -> new StoredProposal(
                        rs.getString("command_sha256")))
                .optional();
    }

    private void appendReceivedEvent(
            SignalProposalCommand command,
            Instant receivedAt
    ) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("proposalId", command.proposalId());
        payload.put("signalType", command.signalType().name());
        payload.put("locationId", command.locationId());
        payload.put("ingredientId", command.ingredientId());
        payload.put("windowStart", command.windowStart());
        payload.put("windowEnd", command.windowEnd());
        payload.put("suggestedQuantity", command.suggestedQuantity());
        payload.put("canonicalUnit", command.canonicalUnit());
        payload.put("reasonCode", command.reasonCode());
        payload.put("status", "PENDING");
        payload.put("executable", false);
        outbox.append(new OutboxEventDraft(
                null,
                "GOVERNED_SIGNAL_PROPOSAL_RECEIVED",
                1,
                command.kitchenId(),
                "governed_signal_proposal",
                command.proposalId(),
                receivedAt,
                command.proposalId(),
                null,
                "signal-proposal-received:" + command.proposalId(),
                payload,
                Map.of(
                        "producer", "bizlama-signal-proposal-worker",
                        "authority", "advisory-only")));
    }

    private void requireConfiguredScope(String kitchenId, String locationId) {
        if (!workspace.kitchenId().equals(kitchenId)
                || !workspace.locationId().equals(locationId)) {
            throw new SignalProposalNotFoundException(
                    "Signal proposal workspace not found.");
        }
    }

    private static String required(String value, int maximum, String label) {
        if (value == null || value.isBlank()) {
            throw new SignalProposalRequestException(label + " is required.");
        }
        String result = value.trim();
        if (result.length() > maximum) {
            throw new SignalProposalRequestException(
                    label + " exceeds " + maximum + " characters.");
        }
        return result;
    }

    private static SignalProposalView mapProposal(
            ResultSet rs,
            int row
    ) throws SQLException {
        return new SignalProposalView(
                rs.getString("proposal_id"),
                rs.getInt("schema_version"),
                rs.getString("command_type"),
                rs.getString("signal_type"),
                rs.getString("risk_tier"),
                rs.getString("kitchen_id"),
                rs.getString("location_id"),
                rs.getString("ingredient_id"),
                rs.getString("window_name"),
                instant(rs, "window_start"),
                instant(rs, "window_end"),
                rs.getBigDecimal("suggested_quantity"),
                rs.getString("canonical_unit"),
                rs.getString("reason_code"),
                rs.getString("evidence_json"),
                rs.getString("status"),
                rs.getInt("version"),
                instant(rs, "received_at"),
                nullableInstant(rs, "dismissed_at"),
                rs.getString("dismissed_by"),
                rs.getString("dismissal_reason"),
                false,
                "RECOMPUTE_USING_OPERATIONAL_DEMAND");
    }

    private static Instant instant(ResultSet rs, String column)
            throws SQLException {
        return rs.getObject(column, OffsetDateTime.class).toInstant();
    }

    private static Instant nullableInstant(ResultSet rs, String column)
            throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    private static final String PROPOSAL_SELECT = """
            SELECT proposal_id, schema_version, command_type, signal_type,
                   risk_tier, kitchen_id, location_id, ingredient_id,
                   window_name, window_start, window_end, suggested_quantity,
                   canonical_unit, reason_code, evidence_json, status, version,
                   received_at, dismissed_at, dismissed_by, dismissal_reason
            FROM governed_signal_proposals
            """;

    enum IngestOutcome {
        ACCEPTED,
        DUPLICATE,
        REJECTED
    }

    record IngestResult(
            IngestOutcome outcome,
            String rejectionCode,
            String proposalId
    ) {
    }

    record DeliveryEvidence(
            String sourceSubscription,
            String messageId,
            Instant publishTime,
            String payloadSha256,
            String payloadBase64,
            String attributesJson,
            Integer deliveryAttempt
    ) {
    }

    private record Scope(
            String baseUnit,
            String rejectionCode,
            String rejectionDetail
    ) {
        static Scope accepted(String baseUnit) {
            return new Scope(baseUnit, null, null);
        }

        static Scope rejected(String code, String detail) {
            return new Scope(null, code, detail);
        }
    }

    private record StoredProposal(String commandSha256) {
    }

    private record StoredDelivery(
            String outcome,
            String rejectionCode,
            String claimedProposalId
    ) {
    }
}
