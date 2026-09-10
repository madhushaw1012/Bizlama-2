package com.bizlama.api.recommendations;

import com.bizlama.api.config.JdbcTimestamp;
import com.bizlama.api.config.WorkspaceProperties;
import com.bizlama.api.outbox.OutboxEventDraft;
import com.bizlama.api.outbox.TransactionalOutboxService;
import com.bizlama.api.quantity.IncompatibleUnitException;
import com.bizlama.api.quantity.UnitConversionService;
import com.bizlama.api.recommendations.DemandCalculation.DemandScope;
import com.bizlama.api.recommendations.DemandCalculation.IngredientDemand;
import com.bizlama.api.recommendations.DemandCalculation.PreparationDemand;
import com.bizlama.api.recommendations.GovernedRecommendation.GenerationResult;
import com.bizlama.api.recommendations.GovernedRecommendation.Outcome;
import com.bizlama.api.recommendations.GovernedRecommendation.OutcomeType;
import com.bizlama.api.recommendations.GovernedRecommendation.RiskTier;
import com.bizlama.api.recommendations.GovernedRecommendation.Status;
import com.bizlama.api.recommendations.GovernedRecommendation.Type;
import com.bizlama.api.recommendations.RecommendationActionService.ActionReference;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Predicate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** Persistence and state transitions for governed deterministic recommendations. */
@Service
public class RecommendationService {

    private static final int SNAPSHOT_SCHEMA_VERSION = 1;
    private static final BigDecimal COMPLETE_CONFIDENCE = new BigDecimal("1.0000");
    private static final BigDecimal DEGRADED_CONFIDENCE = new BigDecimal("0.7500");

    private final JdbcClient jdbc;
    private final DemandCalculationService demand;
    private final UnitConversionService units;
    private final ObjectMapper json;
    private final TransactionalOutboxService outbox;
    private final RecommendationActionService actions;
    private final WorkspaceProperties workspace;

    public RecommendationService(
            JdbcClient jdbc,
            DemandCalculationService demand,
            UnitConversionService units,
            ObjectMapper json,
            TransactionalOutboxService outbox,
            RecommendationActionService actions,
            WorkspaceProperties workspace
    ) {
        this.jdbc = jdbc;
        this.demand = demand;
        this.units = units;
        this.json = json;
        this.outbox = outbox;
        this.actions = actions;
        this.workspace = workspace;
    }

    @Transactional(isolation = Isolation.REPEATABLE_READ)
    public GenerationResult generate(
            DemandScope scope,
            Instant expiresAt,
            String actor
    ) {
        requireText(actor, "Actor");
        Objects.requireNonNull(expiresAt, "Recommendation expiry is required.");
        Instant createdAt = Instant.now();
        if (!expiresAt.isAfter(createdAt)) {
            throw new DemandValidationException(
                    "Recommendation expiry must be in the future."
            );
        }
        if (expiresAt.isAfter(scope.horizonEnd())) {
            throw new DemandValidationException(
                    "Recommendation expiry cannot exceed the calculation horizon."
            );
        }

        lockScope(scope);
        expireDueForScope(scope.kitchenId(), scope.locationId(), createdAt);
        DemandCalculation calculation = demand.calculate(scope);
        Snapshot snapshot = persistSnapshot(calculation, actor);
        appendDemandSnapshotEvents(calculation, snapshot.id());
        List<GovernedRecommendation> generated = new ArrayList<>();
        for (Candidate candidate : candidates(calculation)) {
            GovernedRecommendation recommendation = createRecommendation(
                    scope,
                    candidate,
                    snapshot.id(),
                    expiresAt,
                    actor,
                    createdAt,
                    null
            );
            insertRecommendation(recommendation);
            supersedeMatchingPending(recommendation, actor, createdAt);
            insertDecision(
                    recommendation.id(),
                    recommendation.version(),
                    "CREATED",
                    null,
                    recommendation.proposedQuantity(),
                    actor,
                    "Generated from deterministic demand calculation.",
                    "{\"calculationId\":\"" + snapshot.id() + "\"}",
                    createdAt
            );
            generated.add(recommendation);
        }
        supersedeUnjustifiedPending(scope, snapshot.id(), actor, createdAt);
        return new GenerationResult(
                snapshot.id(),
                calculation,
                List.copyOf(generated)
        );
    }

    @Transactional
    public GovernedRecommendation recommendation(String id) {
        requireText(id, "Recommendation ID");
        expireDueById(id, Instant.now());
        return recommendationQuery("""
                        WHERE id = :id
                          AND kitchen_id = :kitchen
                          AND location_id = :location
                        """)
                .param("id", id)
                .param("kitchen", workspace.kitchenId())
                .param("location", workspace.locationId())
                .query(this::mapRecommendation)
                .optional()
                .orElseThrow(() -> new RecommendationNotFoundException(
                        "Recommendation not found: " + id
                ));
    }

    @Transactional
    public List<GovernedRecommendation> recommendations(
            String kitchenId,
            String locationId,
            Status status
    ) {
        requireText(kitchenId, "Kitchen");
        requireText(locationId, "Location");
        expireDueForScope(kitchenId, locationId, Instant.now());
        if (status == null) {
            return recommendationQuery("""
                            WHERE kitchen_id = :kitchen
                              AND location_id = :location
                            ORDER BY created_at DESC, id
                            """)
                    .param("kitchen", kitchenId)
                    .param("location", locationId)
                    .query(this::mapRecommendation)
                    .list();
        }
        return recommendationQuery("""
                        WHERE kitchen_id = :kitchen
                          AND location_id = :location
                          AND status = :status
                        ORDER BY created_at DESC, id
                        """)
                .param("kitchen", kitchenId)
                .param("location", locationId)
                .param("status", status.name())
                .query(this::mapRecommendation)
                .list();
    }

    @Transactional
    public DemandCalculation calculation(String recommendationId) {
        GovernedRecommendation recommendation = recommendation(recommendationId);
        SnapshotRow snapshot = snapshot(
                recommendation.calculationId(),
                recommendation.kitchenId(),
                recommendation.locationId()
        );
        if (!sha256(snapshot.payloadJson()).equals(snapshot.payloadSha256())) {
            throw new IllegalStateException(
                    "Stored calculation snapshot failed its SHA-256 integrity check."
            );
        }
        try {
            return json.readValue(snapshot.payloadJson(), DemandCalculation.class);
        } catch (JsonProcessingException error) {
            throw new IllegalStateException(
                    "Stored calculation snapshot is unreadable.",
                    error
            );
        }
    }

    @Transactional(isolation = Isolation.REPEATABLE_READ)
    public GovernedRecommendation approve(
            String id,
            int expectedVersion,
            String actor,
            String reason
    ) {
        requireText(actor, "Actor");
        requireText(reason, "Decision reason");
        GovernedRecommendation current = recommendation(id);
        Instant now = Instant.now();
        requirePending(current, expectedVersion, now);
        revalidate(current, now);

        int changed = jdbc.sql("""
                        UPDATE recommendations
                        SET status = 'APPROVED',
                            version = version + 1,
                            updated_at = :now,
                            decided_at = :now,
                            decided_by = :actor,
                            decision_reason = :reason
                        WHERE id = :id
                          AND kitchen_id = :kitchen
                          AND location_id = :location
                          AND version = :version
                          AND status = 'PENDING'
                          AND expires_at > :now
                        """)
                .param("now", JdbcTimestamp.utc(now))
                .param("actor", actor)
                .param("reason", reason)
                .param("id", id)
                .param("kitchen", current.kitchenId())
                .param("location", current.locationId())
                .param("version", expectedVersion)
                .update();
        requireSingleChange(changed);
        insertDecision(
                id,
                expectedVersion + 1,
                "APPROVED",
                current.proposedQuantity(),
                current.proposedQuantity(),
                actor,
                reason,
                "{\"revalidatedAt\":\"" + now + "\"}",
                now
        );
        return recommendation(id);
    }

    @Transactional
    public GovernedRecommendation edit(
            String id,
            int expectedVersion,
            BigDecimal proposedQuantity,
            String actor,
            String reason
    ) {
        requireText(actor, "Actor");
        requireText(reason, "Edit reason");
        if (proposedQuantity == null || proposedQuantity.signum() <= 0) {
            throw new DemandValidationException("Edited quantity must be positive.");
        }
        GovernedRecommendation current = recommendation(id);
        lockScope(current.kitchenId(), current.locationId());
        current = recommendation(id);
        Instant now = Instant.now();
        requirePending(current, expectedVersion, now);

        DemandCalculation refreshed = refreshedCalculation(current, now);
        Candidate justification = matchingCandidate(current, refreshed);
        BigDecimal normalized = normalizeQuantity(proposedQuantity, current.type());
        if (normalized.compareTo(justification.quantity()) > 0) {
            throw new DemandValidationException(
                    "Edited quantity exceeds the currently justified quantity of "
                            + justification.quantity() + " " + justification.unit() + "."
            );
        }
        Snapshot snapshot = persistSnapshot(refreshed, actor);
        String replacementId = UUID.randomUUID().toString();
        GovernedRecommendation replacement = new GovernedRecommendation(
                replacementId,
                current.kitchenId(),
                current.locationId(),
                current.type(),
                current.ingredientId(),
                current.dishId(),
                current.recipeVersionId(),
                normalized,
                current.unit(),
                snapshot.id(),
                justification.confidence(),
                justification.confidenceComponentsJson(),
                justification.reasonCode(),
                current.riskTier(),
                Status.PENDING,
                1,
                current.expiresAt(),
                now,
                actor,
                now,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                current.id(),
                null,
                null,
                null,
                null
        );
        insertRecommendation(replacement);
        int changed = jdbc.sql("""
                        UPDATE recommendations
                        SET status = 'SUPERSEDED',
                            version = version + 1,
                            updated_at = :now,
                            decided_at = :now,
                            decided_by = :actor,
                            decision_reason = :reason,
                            superseded_by_recommendation_id = :replacement
                        WHERE id = :id
                          AND kitchen_id = :kitchen
                          AND location_id = :location
                          AND version = :version
                          AND status = 'PENDING'
                        """)
                .param("now", JdbcTimestamp.utc(now))
                .param("actor", actor)
                .param("reason", reason)
                .param("replacement", replacementId)
                .param("id", id)
                .param("kitchen", current.kitchenId())
                .param("location", current.locationId())
                .param("version", expectedVersion)
                .update();
        requireSingleChange(changed);
        insertDecision(
                id,
                expectedVersion + 1,
                "EDITED",
                current.proposedQuantity(),
                normalized,
                actor,
                reason,
                "{\"replacementRecommendationId\":\"" + replacementId + "\"}",
                now
        );
        insertDecision(
                replacementId,
                1,
                "CREATED",
                current.proposedQuantity(),
                normalized,
                actor,
                "Created by editing recommendation " + id + ".",
                "{\"calculationId\":\"" + snapshot.id() + "\"}",
                now
        );
        return replacement;
    }

    @Transactional
    public GovernedRecommendation dismiss(
            String id,
            int expectedVersion,
            String actor,
            String reason
    ) {
        return terminalPendingDecision(
                id,
                expectedVersion,
                actor,
                reason,
                Status.DISMISSED,
                "DISMISSED"
        );
    }

    @Transactional
    public GovernedRecommendation flagInventory(
            String id,
            int expectedVersion,
            String actor,
            String reason
    ) {
        return terminalPendingDecision(
                id,
                expectedVersion,
                actor,
                reason,
                Status.INVENTORY_FLAGGED,
                "INVENTORY_FLAGGED"
        );
    }

    @Transactional
    public GovernedRecommendation markApplied(
            String id,
            int expectedVersion,
            String actor,
            String reason
    ) {
        requireText(actor, "Actor");
        requireText(reason, "Application reason");
        GovernedRecommendation current = recommendation(id);
        lockScope(current.kitchenId(), current.locationId());
        current = recommendation(id);
        if (current.version() != expectedVersion || current.status() != Status.APPROVED) {
            throw new RecommendationConflictException(
                    "Only the current approved recommendation can be applied."
            );
        }
        Instant now = Instant.now();
        revalidate(current, now);
        ActionReference action = actions.apply(current, now, actor);
        int changed = jdbc.sql("""
                        UPDATE recommendations
                        SET status = 'APPLIED',
                            version = version + 1,
                            updated_at = :now,
                            applied_at = :now,
                            applied_by = :actor,
                            applied_action_type = :actionType,
                            applied_action_id = :actionId
                        WHERE id = :id
                          AND kitchen_id = :kitchen
                          AND location_id = :location
                          AND version = :version
                          AND status = 'APPROVED'
                          AND expires_at > :now
                        """)
                .param("now", JdbcTimestamp.utc(now))
                .param("actor", actor)
                .param("actionType", action.type())
                .param("actionId", action.id())
                .param("id", id)
                .param("kitchen", current.kitchenId())
                .param("location", current.locationId())
                .param("version", expectedVersion)
                .update();
        requireSingleChange(changed);
        insertDecision(
                id,
                expectedVersion + 1,
                "APPLIED",
                current.proposedQuantity(),
                current.proposedQuantity(),
                actor,
                reason,
                "{\"actionType\":\"" + jsonEscape(action.type())
                        + "\",\"actionId\":\"" + jsonEscape(action.id()) + "\"}",
                now
        );
        return recommendation(id);
    }

    @Transactional
    public Outcome recordOutcome(
            String id,
            int expectedVersion,
            OutcomeType type,
            BigDecimal quantity,
            String unit,
            String sourceReferenceType,
            String sourceReferenceId,
            String notes,
            Instant occurredAt,
            String actor
    ) {
        Objects.requireNonNull(type, "Outcome type is required.");
        Objects.requireNonNull(occurredAt, "Outcome time is required.");
        requireText(actor, "Actor");
        Instant recordedAt = Instant.now();
        if (occurredAt.isAfter(recordedAt)) {
            throw new DemandValidationException("Outcome time cannot be in the future.");
        }
        requireText(sourceReferenceType, "Outcome source reference type");
        requireText(sourceReferenceId, "Outcome source reference ID");
        GovernedRecommendation current = recommendation(id);
        boolean appliedOutcome = current.status() == Status.APPLIED;
        boolean inventoryCorrection = current.status() == Status.INVENTORY_FLAGGED
                && type == OutcomeType.CORRECTED_INVENTORY;
        if (current.version() != expectedVersion
                || (!appliedOutcome && !inventoryCorrection)) {
            throw new RecommendationConflictException(
                    "Outcomes require an applied recommendation, or a corrected-inventory "
                            + "outcome for an inventory flag."
            );
        }
        validateOutcomeType(current, type, inventoryCorrection);
        validateOutcomeReference(
                current,
                type,
                sourceReferenceType,
                sourceReferenceId,
                occurredAt
        );
        Instant actionAt = appliedOutcome
                ? current.appliedAt()
                : current.decidedAt();
        if (actionAt == null || occurredAt.isBefore(actionAt)) {
            throw new DemandValidationException(
                    "Outcome time cannot predate the governed recommendation action."
            );
        }

        BigDecimal storedQuantity = null;
        String storedUnit = null;
        boolean quantityRequired = type != OutcomeType.OVERRIDE;
        if (quantityRequired && (quantity == null || unit == null || unit.isBlank())) {
            throw new DemandValidationException(
                    "This measured outcome requires an actual quantity and unit."
            );
        }
        if ((quantity == null) != (unit == null || unit.isBlank())
                || (quantity != null && quantity.signum() <= 0)) {
            throw new DemandValidationException(
                    "Outcome quantity and unit must be supplied together and be positive."
            );
        }
        if (quantity != null) {
            try {
                storedQuantity = units.convert(quantity, unit, current.unit()).quantity()
                        .setScale(6, RoundingMode.HALF_UP);
                storedUnit = current.unit();
            } catch (IncompatibleUnitException error) {
                throw new DemandValidationException(error.getMessage(), error);
            }
        }

        int changed = jdbc.sql("""
                        UPDATE recommendations
                        SET version = version + 1,
                            updated_at = :now
                        WHERE id = :id
                          AND kitchen_id = :kitchen
                          AND location_id = :location
                          AND version = :version
                          AND status IN ('APPLIED', 'INVENTORY_FLAGGED')
                        """)
                .param("now", JdbcTimestamp.utc(recordedAt))
                .param("id", id)
                .param("kitchen", current.kitchenId())
                .param("location", current.locationId())
                .param("version", expectedVersion)
                .update();
        requireSingleChange(changed);

        String outcomeId = UUID.randomUUID().toString();
        int outcomeInserted = jdbc.sql("""
                        INSERT INTO recommendation_outcomes
                        (id, recommendation_id, outcome_type, quantity, unit,
                         source_reference_type, source_reference_id, notes,
                         occurred_at, recorded_at, recorded_by)
                        SELECT
                         :id, recommendation.id, :type, :quantity, :unit,
                         :referenceType, :referenceId, :notes,
                         :occurredAt, :recordedAt, :actor
                        FROM recommendations recommendation
                        WHERE recommendation.id = :recommendation
                          AND recommendation.kitchen_id = :kitchen
                          AND recommendation.location_id = :location
                        """)
                .param("id", outcomeId)
                .param("recommendation", id)
                .param("type", type.name())
                .param("quantity", storedQuantity)
                .param("unit", storedUnit)
                .param("referenceType", sourceReferenceType)
                .param("referenceId", sourceReferenceId)
                .param("notes", notes)
                .param("occurredAt", JdbcTimestamp.utc(occurredAt))
                .param("recordedAt", JdbcTimestamp.utc(recordedAt))
                .param("actor", actor)
                .param("kitchen", current.kitchenId())
                .param("location", current.locationId())
                .update();
        if (outcomeInserted != 1) {
            throw new RecommendationConflictException(
                    "Recommendation left this workspace before its outcome was recorded."
            );
        }
        insertDecision(
                id,
                expectedVersion + 1,
                "OUTCOME_RECORDED",
                current.proposedQuantity(),
                current.proposedQuantity(),
                actor,
                notes == null || notes.isBlank() ? "Outcome recorded." : notes,
                "{\"outcomeId\":\"" + outcomeId
                        + "\",\"outcomeType\":\"" + type.name() + "\"}",
                recordedAt
        );
        appendOutcomeEvent(
                current,
                outcomeId,
                type,
                storedQuantity,
                storedUnit,
                sourceReferenceType,
                sourceReferenceId,
                occurredAt,
                recordedAt,
                actor
        );
        return new Outcome(
                outcomeId,
                id,
                type,
                clean(storedQuantity),
                storedUnit,
                sourceReferenceType,
                sourceReferenceId,
                notes,
                occurredAt,
                recordedAt,
                actor
        );
    }

    @Transactional
    public List<Outcome> outcomes(String recommendationId) {
        GovernedRecommendation recommendation = recommendation(recommendationId);
        return jdbc.sql("""
                        SELECT outcome.id, outcome.recommendation_id,
                               outcome.outcome_type, outcome.quantity, outcome.unit,
                               outcome.source_reference_type,
                               outcome.source_reference_id, outcome.notes,
                               outcome.occurred_at, outcome.recorded_at,
                               outcome.recorded_by
                        FROM recommendation_outcomes outcome
                        WHERE outcome.recommendation_id = :recommendation
                          AND EXISTS (
                            SELECT 1
                            FROM recommendations scoped_recommendation
                            WHERE scoped_recommendation.id = outcome.recommendation_id
                              AND scoped_recommendation.kitchen_id = :kitchen
                              AND scoped_recommendation.location_id = :location)
                        ORDER BY outcome.occurred_at, outcome.id
                        """)
                .param("recommendation", recommendationId)
                .param("kitchen", recommendation.kitchenId())
                .param("location", recommendation.locationId())
                .query((rs, row) -> new Outcome(
                        rs.getString("id"),
                        rs.getString("recommendation_id"),
                        OutcomeType.valueOf(rs.getString("outcome_type")),
                        clean(rs.getBigDecimal("quantity")),
                        rs.getString("unit"),
                        rs.getString("source_reference_type"),
                        rs.getString("source_reference_id"),
                        rs.getString("notes"),
                        instant(rs.getObject("occurred_at", OffsetDateTime.class)),
                        instant(rs.getObject("recorded_at", OffsetDateTime.class)),
                        rs.getString("recorded_by")
                ))
                .list();
    }

    @Transactional
    public GovernedRecommendation reverse(
            String id,
            int expectedVersion,
            String actor,
            String reason
    ) {
        requireText(actor, "Actor");
        requireText(reason, "Reversal reason");
        GovernedRecommendation current = recommendation(id);
        if (current.version() != expectedVersion
                || (current.status() != Status.APPROVED
                    && current.status() != Status.APPLIED)) {
            throw new RecommendationConflictException(
                    "Only a current approved or applied recommendation can be reversed."
            );
        }
        Instant now = Instant.now();
        int changed = jdbc.sql("""
                        UPDATE recommendations
                        SET status = 'REVERSED',
                            version = version + 1,
                            updated_at = :now,
                            reversed_at = :now,
                            reversed_by = :actor,
                            reversal_reason = :reason
                        WHERE id = :id
                          AND kitchen_id = :kitchen
                          AND location_id = :location
                          AND version = :version
                          AND status IN ('APPROVED', 'APPLIED')
                        """)
                .param("now", JdbcTimestamp.utc(now))
                .param("actor", actor)
                .param("reason", reason)
                .param("id", id)
                .param("kitchen", current.kitchenId())
                .param("location", current.locationId())
                .param("version", expectedVersion)
                .update();
        requireSingleChange(changed);
        insertDecision(
                id,
                expectedVersion + 1,
                "REVERSED",
                current.proposedQuantity(),
                current.proposedQuantity(),
                actor,
                reason,
                null,
                now
        );
        return recommendation(id);
    }

    private GovernedRecommendation terminalPendingDecision(
            String id,
            int expectedVersion,
            String actor,
            String reason,
            Status targetStatus,
            String decisionType
    ) {
        requireText(actor, "Actor");
        requireText(reason, "Decision reason");
        GovernedRecommendation current = recommendation(id);
        Instant now = Instant.now();
        requirePending(current, expectedVersion, now);
        int changed = jdbc.sql("""
                        UPDATE recommendations
                        SET status = :status,
                            version = version + 1,
                            updated_at = :now,
                            decided_at = :now,
                            decided_by = :actor,
                            decision_reason = :reason
                        WHERE id = :id
                          AND kitchen_id = :kitchen
                          AND location_id = :location
                          AND version = :version
                          AND status = 'PENDING'
                          AND expires_at > :now
                        """)
                .param("status", targetStatus.name())
                .param("now", JdbcTimestamp.utc(now))
                .param("actor", actor)
                .param("reason", reason)
                .param("id", id)
                .param("kitchen", current.kitchenId())
                .param("location", current.locationId())
                .param("version", expectedVersion)
                .update();
        requireSingleChange(changed);
        insertDecision(
                id,
                expectedVersion + 1,
                decisionType,
                current.proposedQuantity(),
                current.proposedQuantity(),
                actor,
                reason,
                null,
                now
        );
        return recommendation(id);
    }
    private void validateOutcomeType(
            GovernedRecommendation recommendation,
            OutcomeType type,
            boolean inventoryCorrection
    ) {
        if (inventoryCorrection) {
            return;
        }
        boolean compatible = switch (recommendation.type()) {
            case PREPARE -> type == OutcomeType.PREPARED
                    || type == OutcomeType.SOLD
                    || type == OutcomeType.FULFILLED
                    || type == OutcomeType.WASTED
                    || type == OutcomeType.STOCKOUT
                    || type == OutcomeType.OVERRIDE;
            case PURCHASE -> type == OutcomeType.FULFILLED
                    || type == OutcomeType.EMERGENCY_PURCHASED
                    || type == OutcomeType.STOCKOUT
                    || type == OutcomeType.OVERRIDE;
            case UTILISE_EXPIRING_STOCK -> type == OutcomeType.SOLD
                    || type == OutcomeType.FULFILLED
                    || type == OutcomeType.WASTED
                    || type == OutcomeType.OVERRIDE;
            case REDUCE_OR_AVOID_PURCHASE -> type == OutcomeType.WASTED
                    || type == OutcomeType.OVERRIDE;
        };
        if (!compatible) {
            throw new DemandValidationException(
                    "Outcome type " + type
                            + " is not compatible with recommendation type "
                            + recommendation.type() + "."
            );
        }
    }

    private void validateOutcomeReference(
            GovernedRecommendation recommendation,
            OutcomeType outcomeType,
            String sourceReferenceType,
            String sourceReferenceId,
            Instant occurredAt
    ) {
        String type = sourceReferenceType.trim().toUpperCase(Locale.ROOT);
        long matches;
        if (outcomeType == OutcomeType.CORRECTED_INVENTORY) {
            matches = "STOCK_MOVEMENT".equals(type)
                    ? correctionMovementMatches(
                            recommendation,
                            sourceReferenceId,
                            occurredAt
                    )
                    : 0L;
        } else if (type.equals(recommendation.appliedActionType())
                && sourceReferenceId.equals(recommendation.appliedActionId())) {
            matches = jdbc.sql("""
                            SELECT COUNT(*)
                            FROM recommendation_actions
                            WHERE id = :reference
                              AND recommendation_id = :recommendation
                              AND action_type = :type
                              AND kitchen_id = :kitchen
                              AND location_id = :location
                            """)
                    .param("reference", sourceReferenceId)
                    .param("recommendation", recommendation.id())
                    .param("type", type)
                    .param("kitchen", recommendation.kitchenId())
                    .param("location", recommendation.locationId())
                    .query(Long.class)
                    .single();
        } else {
            matches = switch (type) {
                case "ORDER" -> jdbc.sql("""
                                SELECT COUNT(*)
                                FROM customer_orders
                                WHERE id = :reference
                                  AND kitchen_id = :kitchen
                                  AND location_id = :location
                                """)
                        .param("reference", sourceReferenceId)
                        .param("kitchen", recommendation.kitchenId())
                        .param("location", recommendation.locationId())
                        .query(Long.class)
                        .single();
                case "STOCK_MOVEMENT" -> jdbc.sql("""
                                SELECT COUNT(*)
                                FROM stock_movements
                                WHERE id = :reference
                                  AND kitchen_id = :kitchen
                                  AND location_id = :location
                                """)
                        .param("reference", sourceReferenceId)
                        .param("kitchen", recommendation.kitchenId())
                        .param("location", recommendation.locationId())
                        .query(Long.class)
                        .single();
                case "RECEIPT" -> jdbc.sql("""
                                SELECT COUNT(*)
                                FROM receipt_imports
                                WHERE id = :reference
                                  AND kitchen_id = :kitchen
                                  AND location_id = :location
                                """)
                        .param("reference", sourceReferenceId)
                        .param("kitchen", recommendation.kitchenId())
                        .param("location", recommendation.locationId())
                        .query(Long.class)
                        .single();
                default -> 0L;
            };
        }
        if (matches != 1L) {
            throw new DemandValidationException(
                    "Outcome source must reference this recommendation's durable action "
                            + "or a real order, stock movement, or receipt in the same "
                            + "workspace. Corrected inventory requires a post-flag "
                            + "correction or manual-adjustment stock movement for the "
                            + "affected ingredient."
            );
        }
    }

    private long correctionMovementMatches(
            GovernedRecommendation recommendation,
            String sourceReferenceId,
            Instant occurredAt
    ) {
        if (recommendation.decidedAt() == null) {
            return 0L;
        }
        if (recommendation.ingredientId() != null) {
            return jdbc.sql("""
                            SELECT COUNT(*)
                            FROM stock_movements movement
                            WHERE movement.id = :reference
                              AND movement.kitchen_id = :kitchen
                              AND movement.location_id = :location
                              AND movement.ingredient_id = :ingredient
                              AND movement.movement_type IN (
                                  'CORRECTION', 'MANUAL_ADJUSTMENT'
                              )
                              AND movement.occurred_at >= :flaggedAt
                              AND movement.occurred_at <= :outcomeAt
                            """)
                    .param("reference", sourceReferenceId)
                    .param("kitchen", recommendation.kitchenId())
                    .param("location", recommendation.locationId())
                    .param("ingredient", recommendation.ingredientId())
                    .param("flaggedAt", JdbcTimestamp.utc(recommendation.decidedAt()))
                    .param("outcomeAt", JdbcTimestamp.utc(occurredAt))
                    .query(Long.class)
                    .single();
        }
        if (recommendation.recipeVersionId() != null) {
            return jdbc.sql("""
                            SELECT COUNT(*)
                            FROM stock_movements movement
                            WHERE movement.id = :reference
                              AND movement.kitchen_id = :kitchen
                              AND movement.location_id = :location
                              AND movement.movement_type IN (
                                  'CORRECTION', 'MANUAL_ADJUSTMENT'
                              )
                              AND movement.occurred_at >= :flaggedAt
                              AND movement.occurred_at <= :outcomeAt
                              AND EXISTS (
                                  SELECT 1
                                  FROM recipe_ingredients recipe_ingredient
                                  WHERE recipe_ingredient.recipe_version_id = :recipe
                                    AND recipe_ingredient.kitchen_id = :kitchen
                                    AND recipe_ingredient.ingredient_id =
                                        movement.ingredient_id
                              )
                            """)
                    .param("reference", sourceReferenceId)
                    .param("kitchen", recommendation.kitchenId())
                    .param("location", recommendation.locationId())
                    .param("recipe", recommendation.recipeVersionId())
                    .param("flaggedAt", JdbcTimestamp.utc(recommendation.decidedAt()))
                    .param("outcomeAt", JdbcTimestamp.utc(occurredAt))
                    .query(Long.class)
                    .single();
        }
        return 0L;
    }


    private void revalidate(GovernedRecommendation recommendation, Instant now) {
        DemandCalculation refreshed = refreshedCalculation(recommendation, now);
        Candidate current = matchingCandidate(recommendation, refreshed);
        if (recommendation.proposedQuantity().compareTo(current.quantity()) > 0) {
            throw new RecommendationConflictException(
                    "Recommendation is stale: current justified quantity is "
                            + current.quantity() + " " + current.unit() + "."
            );
        }
    }

    private DemandCalculation refreshedCalculation(
            GovernedRecommendation recommendation,
            Instant now
    ) {
        SnapshotRow original = snapshot(
                recommendation.calculationId(),
                recommendation.kitchenId(),
                recommendation.locationId()
        );
        if (!now.isBefore(original.horizonEnd())) {
            throw new RecommendationConflictException(
                    "Recommendation demand horizon has ended."
            );
        }
        DemandScope refreshedScope = new DemandScope(
                recommendation.kitchenId(),
                recommendation.locationId(),
                original.horizonStart(),
                original.horizonEnd(),
                now
        );
        return demand.calculate(refreshedScope);
    }

    private Candidate matchingCandidate(
            GovernedRecommendation recommendation,
            DemandCalculation calculation
    ) {
        Predicate<Candidate> sameTarget = candidate ->
                candidate.type() == recommendation.type()
                        && Objects.equals(
                                candidate.ingredientId(),
                                recommendation.ingredientId()
                        )
                        && Objects.equals(candidate.dishId(), recommendation.dishId())
                        && Objects.equals(
                                candidate.recipeVersionId(),
                                recommendation.recipeVersionId()
                        );
        return candidates(calculation).stream()
                .filter(sameTarget)
                .findFirst()
                .orElseThrow(() -> new RecommendationConflictException(
                        "Recommendation is no longer justified by current demand and stock."
                ));
    }

    private List<Candidate> candidates(DemandCalculation calculation) {
        List<Candidate> result = new ArrayList<>();
        Map<String, IngredientDemand> byIngredient = calculation.ingredients().stream()
                .collect(java.util.stream.Collectors.toMap(
                        IngredientDemand::ingredientId,
                        value -> value
                ));
        for (IngredientDemand ingredient : calculation.ingredients()) {
            if (!ingredient.demandEvidenceComplete()) {
                continue;
            }
            boolean degraded = ingredient.excludedLots().stream()
                    .anyMatch(lot -> "UNRESOLVED_EXPIRY".equals(lot.reason())
                            || "INCOMPATIBLE_UNIT".equals(lot.reason()));
            BigDecimal confidence = degraded
                    ? DEGRADED_CONFIDENCE
                    : COMPLETE_CONFIDENCE;
            String components = "{\"deterministic\":true,"
                    + "\"usableEvidenceComplete\":" + !degraded + "}";

            if (ingredient.shortage().signum() > 0) {
                result.add(new Candidate(
                        Type.PURCHASE,
                        ingredient.ingredientId(),
                        null,
                        null,
                        normalizeQuantity(ingredient.shortage(), Type.PURCHASE),
                        ingredient.canonicalUnit(),
                        confidence,
                        components,
                        "DEMAND_PLUS_SAFETY_EXCEEDS_USABLE_SUPPLY",
                        RiskTier.HIGH
                ));
            }
            if (ingredient.expiryRiskSurplus().signum() > 0) {
                Type type = ingredient.grossDemand().signum() > 0
                        ? Type.UTILISE_EXPIRING_STOCK
                        : Type.REDUCE_OR_AVOID_PURCHASE;
                result.add(new Candidate(
                        type,
                        ingredient.ingredientId(),
                        null,
                        null,
                        normalizeQuantity(ingredient.expiryRiskSurplus(), type),
                        ingredient.canonicalUnit(),
                        confidence,
                        components,
                        type == Type.UTILISE_EXPIRING_STOCK
                                ? "USABLE_LOT_EXPECTED_TO_REMAIN_AT_EXPIRY"
                                : "EXPIRING_SUPPLY_WITHOUT_HORIZON_DEMAND",
                        type == Type.UTILISE_EXPIRING_STOCK
                                ? RiskTier.HIGH
                                : RiskTier.MEDIUM
                ));
            }
        }

        for (PreparationDemand preparation : calculation.preparations()) {
            boolean feasible = preparation.requiredIngredientIds().stream()
                    .map(byIngredient::get)
                    .allMatch(value -> value != null
                            && value.demandEvidenceComplete()
                            && value.timePhasedShortfall().signum() == 0);
            if (!feasible || preparation.quantity().signum() <= 0) {
                continue;
            }
            boolean degraded = preparation.requiredIngredientIds().stream()
                    .map(byIngredient::get)
                    .filter(Objects::nonNull)
                    .flatMap(value -> value.excludedLots().stream())
                    .anyMatch(lot -> "UNRESOLVED_EXPIRY".equals(lot.reason())
                            || "INCOMPATIBLE_UNIT".equals(lot.reason()));
            result.add(new Candidate(
                    Type.PREPARE,
                    null,
                    preparation.dishId(),
                    preparation.recipeVersionId(),
                    normalizeQuantity(preparation.quantity(), Type.PREPARE),
                    preparation.unit(),
                    degraded ? DEGRADED_CONFIDENCE : COMPLETE_CONFIDENCE,
                    "{\"deterministic\":true,\"usableEvidenceComplete\":"
                            + !degraded + "}",
                    "OPEN_ORDER_DEMAND_IS_FEASIBLE_FROM_USABLE_SUPPLY",
                    RiskTier.HIGH
            ));
        }
        return result;
    }

    private Snapshot persistSnapshot(DemandCalculation calculation, String actor) {
        String payload;
        try {
            payload = json.writeValueAsString(calculation);
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("Could not serialize demand calculation.", error);
        }
        String id = UUID.randomUUID().toString();
        String hash = sha256(payload);
        DemandScope scope = calculation.scope();
        jdbc.sql("""
                        INSERT INTO demand_calculation_snapshots
                        (id, kitchen_id, location_id, horizon_start, horizon_end,
                         as_of, schema_version, calculation_method, payload_json,
                         payload_sha256, calculated_at, created_by)
                        VALUES
                        (:id, :kitchen, :location, :horizonStart, :horizonEnd,
                         :asOf, :schemaVersion, :method, :payload,
                         :hash, :calculatedAt, :actor)
                        """)
                .param("id", id)
                .param("kitchen", scope.kitchenId())
                .param("location", scope.locationId())
                .param("horizonStart", JdbcTimestamp.utc(scope.horizonStart()))
                .param("horizonEnd", JdbcTimestamp.utc(scope.horizonEnd()))
                .param("asOf", JdbcTimestamp.utc(scope.asOf()))
                .param("schemaVersion", SNAPSHOT_SCHEMA_VERSION)
                .param("method", calculation.calculationMethod())
                .param("payload", payload)
                .param("hash", hash)
                .param("calculatedAt", JdbcTimestamp.utc(calculation.calculatedAt()))
                .param("actor", actor)
                .update();
        return new Snapshot(id, hash);
    }

    private GovernedRecommendation createRecommendation(
            DemandScope scope,
            Candidate candidate,
            String calculationId,
            Instant expiresAt,
            String actor,
            Instant createdAt,
            String supersedesId
    ) {
        return new GovernedRecommendation(
                UUID.randomUUID().toString(),
                scope.kitchenId(),
                scope.locationId(),
                candidate.type(),
                candidate.ingredientId(),
                candidate.dishId(),
                candidate.recipeVersionId(),
                candidate.quantity(),
                candidate.unit(),
                calculationId,
                candidate.confidence(),
                candidate.confidenceComponentsJson(),
                candidate.reasonCode(),
                candidate.riskTier(),
                Status.PENDING,
                1,
                expiresAt,
                createdAt,
                actor,
                createdAt,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                supersedesId,
                null,
                null,
                null,
                null
        );
    }

    private void insertRecommendation(GovernedRecommendation value) {
        jdbc.sql("""
                        INSERT INTO recommendations
                        (id, kitchen_id, location_id, recommendation_type,
                         ingredient_id, dish_id, recipe_version_id,
                         proposed_quantity, unit, calculation_id,
                         calculation_schema_version, confidence,
                         confidence_components_json, reason_code, risk_tier,
                         status, version, expires_at, created_at, created_by,
                         updated_at, supersedes_recommendation_id)
                        VALUES
                        (:id, :kitchen, :location, :type,
                         :ingredient, :dish, :recipe,
                         :quantity, :unit, :calculation,
                         :schemaVersion, :confidence,
                         :confidenceComponents, :reasonCode, :riskTier,
                         :status, :version, :expiresAt, :createdAt, :createdBy,
                         :updatedAt, :supersedes)
                        """)
                .param("id", value.id())
                .param("kitchen", value.kitchenId())
                .param("location", value.locationId())
                .param("type", value.type().name())
                .param("ingredient", value.ingredientId())
                .param("dish", value.dishId())
                .param("recipe", value.recipeVersionId())
                .param("quantity", value.proposedQuantity())
                .param("unit", value.unit())
                .param("calculation", value.calculationId())
                .param("schemaVersion", SNAPSHOT_SCHEMA_VERSION)
                .param("confidence", value.confidence())
                .param("confidenceComponents", value.confidenceComponentsJson())
                .param("reasonCode", value.reasonCode())
                .param("riskTier", value.riskTier().name())
                .param("status", value.status().name())
                .param("version", value.version())
                .param("expiresAt", JdbcTimestamp.utc(value.expiresAt()))
                .param("createdAt", JdbcTimestamp.utc(value.createdAt()))
                .param("createdBy", value.createdBy())
                .param("updatedAt", JdbcTimestamp.utc(value.updatedAt()))
                .param("supersedes", value.supersedesRecommendationId())
                .update();
    }

    private void supersedeMatchingPending(
            GovernedRecommendation replacement,
            String actor,
            Instant now
    ) {
        List<VersionedId> existing = jdbc.sql("""
                        SELECT id, version
                        FROM recommendations
                        WHERE kitchen_id = :kitchen
                          AND location_id = :location
                          AND recommendation_type = :type
                          AND status = 'PENDING'
                          AND id <> :replacement
                          AND ((ingredient_id IS NULL AND :ingredient IS NULL)
                               OR ingredient_id = :ingredient)
                          AND ((dish_id IS NULL AND :dish IS NULL)
                               OR dish_id = :dish)
                          AND ((recipe_version_id IS NULL AND :recipe IS NULL)
                               OR recipe_version_id = :recipe)
                        ORDER BY created_at DESC, id
                        """)
                .param("kitchen", replacement.kitchenId())
                .param("location", replacement.locationId())
                .param("type", replacement.type().name())
                .param("replacement", replacement.id())
                .param("ingredient", replacement.ingredientId())
                .param("dish", replacement.dishId())
                .param("recipe", replacement.recipeVersionId())
                .query((rs, row) -> new VersionedId(
                        rs.getString("id"),
                        rs.getInt("version")
                ))
                .list();

        for (VersionedId old : existing) {
            int changed = jdbc.sql("""
                            UPDATE recommendations
                            SET status = 'SUPERSEDED',
                                version = version + 1,
                                updated_at = :now,
                                decided_at = :now,
                                decided_by = :actor,
                                decision_reason = :reason,
                                superseded_by_recommendation_id = :replacement
                            WHERE id = :id
                              AND kitchen_id = :kitchen
                              AND location_id = :location
                              AND version = :version
                              AND status = 'PENDING'
                            """)
                    .param("now", JdbcTimestamp.utc(now))
                    .param("actor", actor)
                    .param("reason", "Superseded by a newer deterministic calculation.")
                    .param("replacement", replacement.id())
                    .param("id", old.id())
                    .param("kitchen", replacement.kitchenId())
                    .param("location", replacement.locationId())
                    .param("version", old.version())
                    .update();
            requireSingleChange(changed);
            insertDecision(
                    old.id(),
                    old.version() + 1,
                    "SUPERSEDED",
                    null,
                    replacement.proposedQuantity(),
                    actor,
                    "Superseded by a newer deterministic calculation.",
                    "{\"replacementRecommendationId\":\""
                            + replacement.id() + "\"}",
                    now
            );
        }
    }

    private void lockScope(DemandScope scope) {
        lockScope(scope.kitchenId(), scope.locationId());
    }

    private void lockScope(String kitchenId, String locationId) {
        jdbc.sql("""
                        SELECT id
                        FROM kitchen_locations
                        WHERE kitchen_id = :kitchen
                          AND id = :location
                        FOR UPDATE
                        """)
                .param("kitchen", kitchenId)
                .param("location", locationId)
                .query(String.class)
                .optional()
                .orElseThrow(() -> new DemandValidationException(
                        "Active kitchen/location scope was not found."
                ));
    }

    private void expireDueById(String id, Instant now) {
        expireRows(jdbc.sql("""
                        SELECT id, version, proposed_quantity,
                               kitchen_id, location_id
                        FROM recommendations
                        WHERE id = :id
                          AND kitchen_id = :kitchen
                          AND location_id = :location
                          AND status IN ('PENDING', 'APPROVED')
                          AND expires_at <= :now
                        """)
                .param("id", id)
                .param("kitchen", workspace.kitchenId())
                .param("location", workspace.locationId())
                .param("now", JdbcTimestamp.utc(now))
                .query((rs, row) -> new PendingRecommendation(
                        rs.getString("id"),
                        rs.getInt("version"),
                        rs.getBigDecimal("proposed_quantity"),
                        rs.getString("kitchen_id"),
                        rs.getString("location_id")
                ))
                .list(), now);
    }

    private void expireDueForScope(
            String kitchenId,
            String locationId,
            Instant now
    ) {
        expireRows(jdbc.sql("""
                        SELECT id, version, proposed_quantity,
                               kitchen_id, location_id
                        FROM recommendations
                        WHERE kitchen_id = :kitchen
                          AND location_id = :location
                          AND status IN ('PENDING', 'APPROVED')
                          AND expires_at <= :now
                        ORDER BY id
                        """)
                .param("kitchen", kitchenId)
                .param("location", locationId)
                .param("now", JdbcTimestamp.utc(now))
                .query((rs, row) -> new PendingRecommendation(
                        rs.getString("id"),
                        rs.getInt("version"),
                        rs.getBigDecimal("proposed_quantity"),
                        rs.getString("kitchen_id"),
                        rs.getString("location_id")
                ))
                .list(), now);
    }

    private void expireRows(
            List<PendingRecommendation> rows,
            Instant now
    ) {
        for (PendingRecommendation row : rows) {
            int changed = jdbc.sql("""
                            UPDATE recommendations
                            SET status = 'EXPIRED',
                                version = version + 1,
                                updated_at = :now,
                                decided_at = :now,
                                decided_by = 'system:expiry',
                                decision_reason = 'Recommendation validity window elapsed.'
                            WHERE id = :id
                              AND kitchen_id = :kitchen
                              AND location_id = :location
                              AND version = :version
                              AND status IN ('PENDING', 'APPROVED')
                              AND expires_at <= :now
                            """)
                    .param("now", JdbcTimestamp.utc(now))
                    .param("id", row.id())
                    .param("kitchen", row.kitchenId())
                    .param("location", row.locationId())
                    .param("version", row.version())
                    .update();
            if (changed == 1) {
                insertDecision(
                        row.id(),
                        row.version() + 1,
                        "EXPIRED",
                        row.quantity(),
                        row.quantity(),
                        "system:expiry",
                        "Recommendation validity window elapsed.",
                        null,
                        now
                );
            }
        }
    }

    private void supersedeUnjustifiedPending(
            DemandScope scope,
            String currentCalculationId,
            String actor,
            Instant now
    ) {
        List<PendingRecommendation> stale = jdbc.sql("""
                        SELECT recommendation.id, recommendation.version,
                               recommendation.proposed_quantity,
                               recommendation.kitchen_id,
                               recommendation.location_id
                        FROM recommendations recommendation
                        WHERE recommendation.kitchen_id = :kitchen
                          AND recommendation.location_id = :location
                          AND recommendation.status = 'PENDING'
                          AND recommendation.calculation_id <> :calculation
                        ORDER BY recommendation.id
                        """)
                .param("kitchen", scope.kitchenId())
                .param("location", scope.locationId())
                .param("calculation", currentCalculationId)
                .query((rs, row) -> new PendingRecommendation(
                        rs.getString("id"),
                        rs.getInt("version"),
                        rs.getBigDecimal("proposed_quantity"),
                        rs.getString("kitchen_id"),
                        rs.getString("location_id")
                ))
                .list();

        for (PendingRecommendation row : stale) {
            int changed = jdbc.sql("""
                            UPDATE recommendations
                            SET status = 'SUPERSEDED',
                                version = version + 1,
                                updated_at = :now,
                                decided_at = :now,
                                decided_by = :actor,
                                decision_reason = :reason
                            WHERE id = :id
                              AND kitchen_id = :kitchen
                              AND location_id = :location
                              AND version = :version
                              AND status = 'PENDING'
                            """)
                    .param("now", JdbcTimestamp.utc(now))
                    .param("actor", actor)
                    .param("reason",
                            "No longer justified by the newer deterministic calculation.")
                    .param("id", row.id())
                    .param("kitchen", scope.kitchenId())
                    .param("location", scope.locationId())
                    .param("version", row.version())
                    .update();
            if (changed == 1) {
                insertDecision(
                        row.id(),
                        row.version() + 1,
                        "SUPERSEDED",
                        row.quantity(),
                        null,
                        actor,
                        "No longer justified by the newer deterministic calculation.",
                        "{\"calculationId\":\"" + currentCalculationId + "\"}",
                        now
                );
            }
        }
    }

    private void insertDecision(
            String recommendationId,
            int recommendationVersion,
            String decisionType,
            BigDecimal previousQuantity,
            BigDecimal newQuantity,
            String actor,
            String reason,
            String detailsJson,
            Instant decidedAt
    ) {
        int inserted = jdbc.sql("""
                        INSERT INTO recommendation_decisions
                        (id, recommendation_id, recommendation_version,
                         decision_type, previous_quantity, new_quantity,
                         actor, reason, details_json, decided_at)
                        SELECT
                         :id, recommendation.id, :version,
                         :type, :previousQuantity, :newQuantity,
                         :actor, :reason, :details, :decidedAt
                        FROM recommendations recommendation
                        WHERE recommendation.id = :recommendation
                          AND recommendation.kitchen_id = :kitchen
                          AND recommendation.location_id = :location
                        """)
                .param("id", UUID.randomUUID().toString())
                .param("recommendation", recommendationId)
                .param("version", recommendationVersion)
                .param("type", decisionType)
                .param("previousQuantity", previousQuantity)
                .param("newQuantity", newQuantity)
                .param("actor", actor)
                .param("reason", reason)
                .param("details", detailsJson)
                .param("decidedAt", JdbcTimestamp.utc(decidedAt))
                .param("kitchen", workspace.kitchenId())
                .param("location", workspace.locationId())
                .update();
        if (inserted != 1) {
            throw new RecommendationConflictException(
                    "Recommendation left this workspace before its decision was recorded."
            );
        }
        appendDecisionEvent(
                recommendationId,
                recommendationVersion,
                decisionType,
                actor,
                reason,
                detailsJson,
                decidedAt
        );
    }

    private void appendDecisionEvent(
            String recommendationId,
            int version,
            String decisionType,
            String actor,
            String reason,
            String detailsJson,
            Instant occurredAt
    ) {
        RecommendationEventRow row = jdbc.sql("""
                        SELECT kitchen_id, location_id, recommendation_type,
                               ingredient_id, dish_id, recipe_version_id,
                               proposed_quantity, unit, calculation_id, status
                        FROM recommendations
                        WHERE id = :id
                          AND kitchen_id = :kitchen
                          AND location_id = :location
                        """)
                .param("id", recommendationId)
                .param("kitchen", workspace.kitchenId())
                .param("location", workspace.locationId())
                .query((rs, ignored) -> new RecommendationEventRow(
                        rs.getString("kitchen_id"),
                        rs.getString("location_id"),
                        rs.getString("recommendation_type"),
                        rs.getString("ingredient_id"),
                        rs.getString("dish_id"),
                        rs.getString("recipe_version_id"),
                        rs.getBigDecimal("proposed_quantity"),
                        rs.getString("unit"),
                        rs.getString("calculation_id"),
                        rs.getString("status")
                ))
                .single();

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("recommendationId", recommendationId);
        payload.put("recommendationVersion", version);
        payload.put("decisionType", decisionType);
        payload.put("status", row.status());
        payload.put("recommendationType", row.recommendationType());
        payload.put("locationId", row.locationId());
        payload.put("proposedQuantity", row.quantity());
        payload.put("unit", row.unit());
        payload.put("actor", actor);
        payload.put("reason", reason);
        putIfPresent(payload, "ingredientId", row.ingredientId());
        putIfPresent(payload, "dishId", row.dishId());
        putIfPresent(payload, "recipeVersionId", row.recipeVersionId());
        putIfPresent(payload, "detailsJson", detailsJson);

        String eventType = "CREATED".equals(decisionType)
                ? "RECOMMENDATION_CREATED"
                : "RECOMMENDATION_DECIDED";
        outbox.append(new OutboxEventDraft(
                null,
                eventType,
                1,
                row.kitchenId(),
                "recommendation",
                recommendationId,
                occurredAt,
                row.calculationId(),
                null,
                "recommendation:" + recommendationId + ":v" + version
                        + ":" + decisionType,
                payload,
                Map.of("producer", "bizlama-api")
        ));
    }
    private void appendDemandSnapshotEvents(
            DemandCalculation calculation,
            String calculationId
    ) {
        DemandScope scope = calculation.scope();
        for (IngredientDemand ingredient : calculation.ingredients()) {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("calculationId", calculationId);
            payload.put("locationId", scope.locationId());
            payload.put("ingredientId", ingredient.ingredientId());
            payload.put("canonicalUnit", ingredient.canonicalUnit());
            payload.put("grossDemand", ingredient.grossDemand());
            payload.put("usableSupply", ingredient.usableSupply());
            payload.put("safetyStock", ingredient.safetyStock());
            payload.put("shortage", ingredient.shortage());
            payload.put("expiryRiskSurplus", ingredient.expiryRiskSurplus());
            payload.put("horizonStart", scope.horizonStart());
            payload.put("horizonEnd", scope.horizonEnd());
            payload.put("asOf", scope.asOf());
            payload.put("calculationMethod", calculation.calculationMethod());
            payload.put(
                    "contributingOrderIds",
                    ingredient.contributingOrderIds()
            );
            payload.put(
                    "contributingRecipeVersionIds",
                    ingredient.contributingRecipeVersionIds()
            );

            String entityId = calculationId + ":" + ingredient.ingredientId();
            outbox.append(new OutboxEventDraft(
                    null,
                    "DEMAND_CALCULATION_SNAPSHOT",
                    1,
                    scope.kitchenId(),
                    "demand_calculation_ingredient",
                    entityId,
                    calculation.calculatedAt(),
                    calculationId,
                    null,
                    "demand-calculation:" + entityId,
                    payload,
                    Map.of(
                            "producer", "bizlama-api",
                            "authority", DemandCalculation.METHOD
                    )
            ));
        }
    }


    private void appendOutcomeEvent(
            GovernedRecommendation recommendation,
            String outcomeId,
            OutcomeType type,
            BigDecimal quantity,
            String unit,
            String sourceReferenceType,
            String sourceReferenceId,
            Instant occurredAt,
            Instant recordedAt,
            String actor
    ) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("outcomeId", outcomeId);
        payload.put("recommendationId", recommendation.id());
        payload.put("recommendationType", recommendation.type().name());
        payload.put("locationId", recommendation.locationId());
        putIfPresent(payload, "ingredientId", recommendation.ingredientId());
        putIfPresent(payload, "dishId", recommendation.dishId());
        payload.put("outcomeType", type.name());
        payload.put("recordedAt", recordedAt);
        payload.put("actor", actor);
        putIfPresent(payload, "quantity", quantity);
        putIfPresent(payload, "unit", unit);
        putIfPresent(payload, "sourceReferenceType", sourceReferenceType);
        putIfPresent(payload, "sourceReferenceId", sourceReferenceId);
        outbox.append(new OutboxEventDraft(
                null,
                "RECOMMENDATION_OUTCOME_RECORDED",
                1,
                recommendation.kitchenId(),
                "recommendation_outcome",
                outcomeId,
                occurredAt,
                recommendation.calculationId(),
                recommendation.id(),
                "recommendation-outcome:" + outcomeId,
                payload,
                Map.of("producer", "bizlama-api")
        ));
    }

    private static void putIfPresent(
            Map<String, Object> target,
            String key,
            Object value
    ) {
        if (value != null) {
            target.put(key, value);
        }
    }

    private SnapshotRow snapshot(
            String id,
            String kitchenId,
            String locationId
    ) {
        SnapshotRow result = jdbc.sql("""
                        SELECT id, kitchen_id, location_id, horizon_start,
                               horizon_end, as_of, payload_json, payload_sha256
                        FROM demand_calculation_snapshots
                        WHERE id = :id
                          AND kitchen_id = :kitchen
                          AND location_id = :location
                        """)
                .param("id", id)
                .param("kitchen", kitchenId)
                .param("location", locationId)
                .query((rs, row) -> new SnapshotRow(
                        rs.getString("id"),
                        rs.getString("kitchen_id"),
                        rs.getString("location_id"),
                        instant(rs.getObject("horizon_start", OffsetDateTime.class)),
                        instant(rs.getObject("horizon_end", OffsetDateTime.class)),
                        instant(rs.getObject("as_of", OffsetDateTime.class)),
                        rs.getString("payload_json"),
                        rs.getString("payload_sha256")
                ))
                .optional()
                .orElseThrow(() -> new IllegalStateException(
                        "Recommendation calculation snapshot is missing."
                ));
        if (!sha256(result.payloadJson()).equals(result.payloadSha256())) {
            throw new IllegalStateException(
                    "Stored calculation snapshot failed its SHA-256 integrity check."
            );
        }
        return result;
    }

    private JdbcClient.StatementSpec recommendationQuery(String suffix) {
        return jdbc.sql("""
                        SELECT id, kitchen_id, location_id, recommendation_type,
                               ingredient_id, dish_id, recipe_version_id,
                               proposed_quantity, unit, calculation_id, confidence,
                               confidence_components_json, reason_code, risk_tier,
                               status, version, expires_at, created_at, created_by,
                               updated_at, decided_at, decided_by, decision_reason,
                               applied_at, applied_by, applied_action_type,
                               applied_action_id, supersedes_recommendation_id,
                               superseded_by_recommendation_id, reversed_at,
                               reversed_by, reversal_reason
                        FROM recommendations
                        """ + suffix);
    }

    private GovernedRecommendation mapRecommendation(
            java.sql.ResultSet rs,
            int row
    ) throws java.sql.SQLException {
        return new GovernedRecommendation(
                rs.getString("id"),
                rs.getString("kitchen_id"),
                rs.getString("location_id"),
                Type.valueOf(rs.getString("recommendation_type")),
                rs.getString("ingredient_id"),
                rs.getString("dish_id"),
                rs.getString("recipe_version_id"),
                clean(rs.getBigDecimal("proposed_quantity")),
                rs.getString("unit"),
                rs.getString("calculation_id"),
                rs.getBigDecimal("confidence"),
                rs.getString("confidence_components_json"),
                rs.getString("reason_code"),
                RiskTier.valueOf(rs.getString("risk_tier")),
                Status.valueOf(rs.getString("status")),
                rs.getInt("version"),
                instant(rs.getObject("expires_at", OffsetDateTime.class)),
                instant(rs.getObject("created_at", OffsetDateTime.class)),
                rs.getString("created_by"),
                instant(rs.getObject("updated_at", OffsetDateTime.class)),
                instant(rs.getObject("decided_at", OffsetDateTime.class)),
                rs.getString("decided_by"),
                rs.getString("decision_reason"),
                instant(rs.getObject("applied_at", OffsetDateTime.class)),
                rs.getString("applied_by"),
                rs.getString("applied_action_type"),
                rs.getString("applied_action_id"),
                rs.getString("supersedes_recommendation_id"),
                rs.getString("superseded_by_recommendation_id"),
                instant(rs.getObject("reversed_at", OffsetDateTime.class)),
                rs.getString("reversed_by"),
                rs.getString("reversal_reason")
        );
    }

    private static void requirePending(
            GovernedRecommendation recommendation,
            int expectedVersion,
            Instant now
    ) {
        if (recommendation.version() != expectedVersion
                || recommendation.status() != Status.PENDING) {
            throw new RecommendationConflictException(
                    "Recommendation changed; reload it before deciding."
            );
        }
        if (!recommendation.expiresAt().isAfter(now)) {
            throw new RecommendationConflictException("Recommendation has expired.");
        }
    }

    private static void requireSingleChange(int changed) {
        if (changed != 1) {
            throw new RecommendationConflictException(
                    "Recommendation changed concurrently; reload before retrying."
            );
        }
    }

    private static BigDecimal normalizeQuantity(BigDecimal value, Type type) {
        RoundingMode rounding = type == Type.PURCHASE
                ? RoundingMode.CEILING
                : RoundingMode.HALF_UP;
        BigDecimal normalized = value.setScale(6, rounding);
        if (normalized.signum() <= 0) {
            throw new DemandValidationException(
                    "Recommendation quantity rounds to zero at canonical precision."
            );
        }
        return normalized;
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 is unavailable.", error);
        }
    }

    private static String jsonEscape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static BigDecimal clean(BigDecimal value) {
        if (value == null) {
            return null;
        }
        if (value.signum() == 0) {
            return BigDecimal.ZERO;
        }
        return value.stripTrailingZeros();
    }

    private static Instant instant(OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }

    private static void requireText(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new DemandValidationException(label + " is required.");
        }
    }

    private record Candidate(
            Type type,
            String ingredientId,
            String dishId,
            String recipeVersionId,
            BigDecimal quantity,
            String unit,
            BigDecimal confidence,
            String confidenceComponentsJson,
            String reasonCode,
            RiskTier riskTier
    ) {
    }

    private record Snapshot(String id, String hash) {
    }

    private record SnapshotRow(
            String id,
            String kitchenId,
            String locationId,
            Instant horizonStart,
            Instant horizonEnd,
            Instant asOf,
            String payloadJson,
            String payloadSha256
    ) {
    }

    private record PendingRecommendation(
            String id,
            int version,
            BigDecimal quantity,
            String kitchenId,
            String locationId
    ) {
    }

    private record VersionedId(String id, int version) {
    }

    private record RecommendationEventRow(
            String kitchenId,
            String locationId,
            String recommendationType,
            String ingredientId,
            String dishId,
            String recipeVersionId,
            BigDecimal quantity,
            String unit,
            String calculationId,
            String status
    ) {
    }
}
