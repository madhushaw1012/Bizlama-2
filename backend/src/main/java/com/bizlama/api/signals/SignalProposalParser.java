package com.bizlama.api.signals;

import com.bizlama.api.outbox.CanonicalJson;
import com.bizlama.api.signals.SignalProposalCommand.SignalType;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/** Strict parser for the signal engine's version-one proposal command. */
@Component
public class SignalProposalParser {

    static final String COMMAND_TYPE =
            "CREATE_GOVERNED_RECOMMENDATION_PROPOSAL";
    static final Set<String> ATTRIBUTE_FIELDS = Set.of(
            "proposalId", "signalTime", "schemaVersion", "commandType",
            "kitchenId", "locationId");

    private static final Set<String> COMMAND_FIELDS = Set.of(
            "proposalId", "schemaVersion", "commandType", "signalType",
            "riskTier", "kitchenId", "locationId", "ingredientId", "windowName",
            "windowStartMillis", "windowEndMillis", "proposedQuantity",
            "canonicalUnit", "reasonCode", "evidenceJson", "targetQueue",
            "directMutationAllowed");
    private static final Set<String> EVIDENCE_FIELDS = Set.of(
            "eventCount", "dataQualityEvents", "windowDemand",
            "snapshotGrossDemand", "snapshotUsableSupply",
            "snapshotSafetyStock", "snapshotShortage",
            "snapshotExpiryRiskSurplus");
    private static final Pattern PROPOSAL_ID = Pattern.compile(
            "SIG-[0-9a-f]{24}");
    private static final Pattern ENTITY_ID = Pattern.compile(
            "[A-Za-z0-9][A-Za-z0-9._:-]{0,99}");

    private final ObjectMapper objectMapper;
    private final CanonicalJson canonicalJson;

    public SignalProposalParser(
            ObjectMapper objectMapper,
            CanonicalJson canonicalJson
    ) {
        this.objectMapper = objectMapper.copy()
                .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
        this.canonicalJson = canonicalJson;
    }

    SignalProposalCommand parse(
            byte[] body,
            String expectedTargetQueue,
            int maxMessageBytes
    ) {
        if (body == null || body.length == 0) {
            throw invalid("EMPTY_BODY", "Proposal command body must not be empty.");
        }
        if (body.length > maxMessageBytes) {
            throw invalid(
                    "BODY_TOO_LARGE",
                    "Proposal command body exceeds the configured size limit.");
        }

        JsonNode root;
        try {
            root = objectMapper.readTree(body);
        } catch (IOException error) {
            throw new SignalProposalValidationException(
                    "MALFORMED_JSON",
                    "Proposal command body is not strict JSON.",
                    null,
                    error);
        }
        if (root == null || !root.isObject()) {
            throw invalid("INVALID_SCHEMA", "Proposal command must be an object.");
        }
        String claimedId = optionalText(root, "proposalId");
        requireExactFields(root, COMMAND_FIELDS, "command", claimedId);

        String proposalId = requiredText(root, "proposalId", claimedId);
        if (!PROPOSAL_ID.matcher(proposalId).matches()) {
            throw invalid(
                    "INVALID_PROPOSAL_ID",
                    "proposalId must be SIG- followed by 24 lowercase hex characters.",
                    claimedId);
        }
        int schemaVersion = requiredInteger(root, "schemaVersion", claimedId);
        if (schemaVersion != 1) {
            throw invalid(
                    "UNSUPPORTED_SCHEMA_VERSION",
                    "Only proposal schemaVersion 1 is supported.",
                    claimedId);
        }
        String commandType = requiredText(root, "commandType", claimedId);
        if (!COMMAND_TYPE.equals(commandType)) {
            throw invalid(
                    "UNSUPPORTED_COMMAND_TYPE",
                    "commandType is not supported by the governed inbox.",
                    claimedId);
        }

        SignalType signalType;
        try {
            signalType = SignalType.valueOf(
                    requiredText(root, "signalType", claimedId));
        } catch (IllegalArgumentException error) {
            throw new SignalProposalValidationException(
                    "UNSUPPORTED_SIGNAL_TYPE",
                    "signalType is not supported by proposal schemaVersion 1.",
                    claimedId,
                    error);
        }
        String riskTier = requiredText(root, "riskTier", claimedId);
        if (!"HIGH".equals(riskTier)) {
            throw invalid(
                    "INVALID_RISK_TIER",
                    "Signal proposals must remain HIGH risk.",
                    claimedId);
        }
        String kitchenId = entityId(root, "kitchenId", claimedId);
        String locationId = entityId(root, "locationId", claimedId);
        String ingredientId = entityId(root, "ingredientId", claimedId);
        String windowName = requiredText(root, "windowName", claimedId);
        long windowStartMillis = requiredLong(
                root, "windowStartMillis", claimedId);
        long windowEndMillis = requiredLong(root, "windowEndMillis", claimedId);
        validateWindow(
                windowName, windowStartMillis, windowEndMillis, claimedId);

        BigDecimal proposedQuantity = nullableDecimal(
                root, "proposedQuantity", claimedId);
        String canonicalUnit = nullableNonBlankText(
                root, "canonicalUnit", claimedId);
        String reasonCode = requiredText(root, "reasonCode", claimedId);
        String evidenceJson = requiredText(root, "evidenceJson", claimedId);
        Evidence evidence = evidence(evidenceJson, claimedId);
        validateSignal(
                signalType,
                proposedQuantity,
                canonicalUnit,
                reasonCode,
                evidence,
                claimedId);

        String targetQueue = requiredText(root, "targetQueue", claimedId);
        if (!expectedTargetQueue.equals(targetQueue)) {
            throw invalid(
                    "WRONG_TARGET_QUEUE",
                    "targetQueue does not match this governed consumer.",
                    claimedId);
        }
        JsonNode directMutation = root.get("directMutationAllowed");
        if (!directMutation.isBoolean() || directMutation.booleanValue()) {
            throw invalid(
                    "DIRECT_MUTATION_FORBIDDEN",
                    "directMutationAllowed must be the boolean false.",
                    claimedId);
        }

        String seed = kitchenId + "|" + locationId + "|" + ingredientId
                + "|" + windowName + "|" + windowStartMillis
                + "|" + signalType.name();
        String expectedProposalId = "SIG-" + sha256(seed).substring(0, 24);
        if (!expectedProposalId.equals(proposalId)) {
            throw invalid(
                    "PROPOSAL_ID_MISMATCH",
                    "proposalId does not match the deterministic signal evidence.",
                    claimedId);
        }

        Map<String, Object> canonicalCommand = new LinkedHashMap<>();
        canonicalCommand.put("proposalId", proposalId);
        canonicalCommand.put("schemaVersion", schemaVersion);
        canonicalCommand.put("commandType", commandType);
        canonicalCommand.put("signalType", signalType.name());
        canonicalCommand.put("riskTier", riskTier);
        canonicalCommand.put("kitchenId", kitchenId);
        canonicalCommand.put("locationId", locationId);
        canonicalCommand.put("ingredientId", ingredientId);
        canonicalCommand.put("windowName", windowName);
        canonicalCommand.put("windowStartMillis", windowStartMillis);
        canonicalCommand.put("windowEndMillis", windowEndMillis);
        canonicalCommand.put("proposedQuantity", canonicalDecimal(proposedQuantity));
        canonicalCommand.put("canonicalUnit", canonicalUnit);
        canonicalCommand.put("reasonCode", reasonCode);
        canonicalCommand.put("evidenceJson", evidenceJson);
        canonicalCommand.put("targetQueue", targetQueue);
        canonicalCommand.put("directMutationAllowed", false);

        return new SignalProposalCommand(
                proposalId,
                schemaVersion,
                commandType,
                signalType,
                riskTier,
                kitchenId,
                locationId,
                ingredientId,
                windowName,
                Instant.ofEpochMilli(windowStartMillis),
                Instant.ofEpochMilli(windowEndMillis),
                proposedQuantity,
                canonicalUnit,
                reasonCode,
                evidenceJson,
                targetQueue,
                false,
                sha256(canonicalJson.write(canonicalCommand)));
    }

    void validateAttributes(
            Map<String, String> attributes,
            SignalProposalCommand command
    ) {
        if (attributes == null || !attributes.keySet().equals(ATTRIBUTE_FIELDS)) {
            throw invalid(
                    "INVALID_ATTRIBUTES",
                    "Pub/Sub attributes must contain exactly proposalId, signalTime, "
                            + "schemaVersion, commandType, kitchenId, and locationId.",
                    command.proposalId());
        }
        if (!command.proposalId().equals(attributes.get("proposalId"))
                || !Integer.toString(command.schemaVersion()).equals(
                        attributes.get("schemaVersion"))
                || !command.commandType().equals(attributes.get("commandType"))
                || !command.kitchenId().equals(attributes.get("kitchenId"))
                || !command.locationId().equals(attributes.get("locationId"))) {
            throw invalid(
                    "ATTRIBUTE_BODY_MISMATCH",
                    "Pub/Sub identity attributes do not match the command body.",
                    command.proposalId());
        }
        try {
            Instant signalTime = Instant.parse(attributes.get("signalTime"));
            if (!signalTime.equals(command.windowEnd())) {
                throw invalid(
                        "ATTRIBUTE_BODY_MISMATCH",
                        "Pub/Sub signalTime does not match windowEndMillis.",
                        command.proposalId());
            }
        } catch (java.time.DateTimeException error) {
            throw new SignalProposalValidationException(
                    "INVALID_ATTRIBUTES",
                    "Pub/Sub signalTime must be an ISO-8601 instant.",
                    command.proposalId(),
                    error);
        }
    }

    private Evidence evidence(String json, String claimedId) {
        JsonNode root;
        try {
            root = objectMapper.readTree(json);
        } catch (JsonProcessingException error) {
            throw new SignalProposalValidationException(
                    "INVALID_EVIDENCE",
                    "evidenceJson is not strict JSON.",
                    claimedId,
                    error);
        }
        if (root == null || !root.isObject()) {
            throw invalid(
                    "INVALID_EVIDENCE", "evidenceJson must contain an object.", claimedId);
        }
        requireExactFields(root, EVIDENCE_FIELDS, "evidence", claimedId);
        long eventCount = requiredNonNegativeLong(root, "eventCount", claimedId);
        long dataQualityEvents = requiredNonNegativeLong(
                root, "dataQualityEvents", claimedId);
        if (eventCount < 1) {
            throw invalid(
                    "INVALID_EVIDENCE", "eventCount must be positive.", claimedId);
        }
        BigDecimal windowDemand = decimalString(
                root, "windowDemand", false, claimedId);
        BigDecimal gross = decimalString(
                root, "snapshotGrossDemand", true, claimedId);
        BigDecimal usable = decimalString(
                root, "snapshotUsableSupply", true, claimedId);
        BigDecimal safety = decimalString(
                root, "snapshotSafetyStock", true, claimedId);
        BigDecimal shortage = decimalString(
                root, "snapshotShortage", true, claimedId);
        BigDecimal expiry = decimalString(
                root, "snapshotExpiryRiskSurplus", true, claimedId);
        Evidence result = new Evidence(
                eventCount,
                dataQualityEvents,
                windowDemand,
                gross,
                usable,
                safety,
                shortage,
                expiry);
        if (!result.exactJson().equals(json)) {
            throw invalid(
                    "NON_CANONICAL_EVIDENCE",
                    "evidenceJson does not match the signal engine canonical form.",
                    claimedId);
        }
        return result;
    }

    private void validateSignal(
            SignalType type,
            BigDecimal proposed,
            String unit,
            String reason,
            Evidence evidence,
            String claimedId
    ) {
        switch (type) {
            case SHORTAGE_RISK -> {
                requireReason(
                        reason,
                        "AUTHORITATIVE_DEMAND_SNAPSHOT_SHORTAGE",
                        claimedId);
                if (evidence.gross() == null || evidence.usable() == null
                        || evidence.safety() == null
                        || evidence.shortage() == null
                        || evidence.shortage().signum() <= 0) {
                    throw invalid(
                            "INVALID_SIGNAL_EVIDENCE",
                            "SHORTAGE_RISK requires a positive authoritative "
                                    + "snapshot shortage and its source evidence.",
                            claimedId);
                }
                requireExactPositiveQuantity(
                        proposed, evidence.shortage(), unit, claimedId);
            }
            case EXPIRY_RISK_SURPLUS -> {
                requireReason(
                        reason,
                        "AUTHORITATIVE_SNAPSHOT_EXPIRY_RISK_SURPLUS",
                        claimedId);
                if (evidence.expiry() == null || evidence.expiry().signum() <= 0) {
                    throw invalid(
                            "INVALID_SIGNAL_EVIDENCE",
                            "EXPIRY_RISK_SURPLUS requires positive snapshot surplus.",
                            claimedId);
                }
                requireExactPositiveQuantity(
                        proposed, evidence.expiry(), unit, claimedId);
            }
            case MATERIAL_DATA_QUALITY -> {
                requireReason(
                        reason,
                        "MATERIAL_CANONICAL_DATA_QUALITY_RATE",
                        claimedId);
                boolean material = evidence.dataQualityEvents() >= 3
                        || (evidence.eventCount() >= 2
                        && BigInteger.valueOf(evidence.dataQualityEvents())
                                .multiply(BigInteger.valueOf(4))
                                .compareTo(BigInteger.valueOf(
                                        evidence.eventCount())) >= 0);
                if (!material || proposed != null) {
                    throw invalid(
                            "INVALID_SIGNAL_EVIDENCE",
                            "MATERIAL_DATA_QUALITY must satisfy the producer threshold "
                                    + "and have no suggested quantity.",
                            claimedId);
                }
            }
        }
    }

    private void requireExactPositiveQuantity(
            BigDecimal proposed,
            BigDecimal expected,
            String unit,
            String claimedId
    ) {
        if (proposed == null || proposed.signum() <= 0 || unit == null
                || proposed.compareTo(expected) != 0) {
            throw invalid(
                    "INVALID_SUGGESTED_QUANTITY",
                    "Suggested quantity/unit must exactly match derived signal evidence.",
                    claimedId);
        }
    }

    private static void requireReason(
            String actual,
            String expected,
            String claimedId
    ) {
        if (!expected.equals(actual)) {
            throw invalid(
                    "INVALID_REASON_CODE",
                    "reasonCode does not match signalType.",
                    claimedId);
        }
    }

    private static void validateWindow(
            String name,
            long start,
            long end,
            String claimedId
    ) {
        long duration = switch (name) {
            case "15m" -> 15L * 60 * 1000;
            case "1h" -> 60L * 60 * 1000;
            case "daily" -> 24L * 60 * 60 * 1000;
            default -> throw invalid(
                    "INVALID_WINDOW", "windowName is not supported.", claimedId);
        };
        if (start < 0 || start % duration != 0
                || start > Long.MAX_VALUE - duration
                || end != start + duration) {
            throw invalid(
                    "INVALID_WINDOW",
                    "Window boundaries must match the named fixed UTC window.",
                    claimedId);
        }
    }

    private static BigDecimal decimalString(
            JsonNode root,
            String field,
            boolean nullable,
            String claimedId
    ) {
        JsonNode value = root.get(field);
        if (nullable && value.isNull()) {
            return null;
        }
        if (!value.isTextual() || value.textValue().isBlank()) {
            throw invalid(
                    "INVALID_EVIDENCE",
                    field + " must be a canonical decimal string"
                            + (nullable ? " or null." : "."),
                    claimedId);
        }
        try {
            BigDecimal result = new BigDecimal(value.textValue());
            if (result.signum() < 0
                    || !result.toPlainString().equals(value.textValue())) {
                throw new NumberFormatException("non-canonical or negative");
            }
            return result;
        } catch (NumberFormatException error) {
            throw new SignalProposalValidationException(
                    "INVALID_EVIDENCE",
                    field + " must be a non-negative plain decimal string.",
                    claimedId,
                    error);
        }
    }

    private static BigDecimal nullableDecimal(
            JsonNode root,
            String field,
            String claimedId
    ) {
        JsonNode value = root.get(field);
        if (value.isNull()) {
            return null;
        }
        if (!value.isNumber()) {
            throw invalid(
                    "INVALID_SCHEMA", field + " must be a JSON number or null.", claimedId);
        }
        try {
            BigDecimal result = value.decimalValue();
            BigDecimal normalized = canonicalDecimal(result);
            int integerDigits = normalized.precision() - normalized.scale();
            if (normalized.scale() > 12 || integerDigits > 18) {
                throw invalid(
                        "SUGGESTED_QUANTITY_OUT_OF_RANGE",
                        "proposedQuantity exceeds the governed inbox precision.",
                        claimedId);
            }
            return result;
        } catch (ArithmeticException error) {
            throw new SignalProposalValidationException(
                    "INVALID_SCHEMA",
                    field + " must be a finite decimal number.",
                    claimedId,
                    error);
        }
    }

    private static BigDecimal canonicalDecimal(BigDecimal value) {
        if (value == null) {
            return null;
        }
        return value.signum() == 0 ? BigDecimal.ZERO : value.stripTrailingZeros();
    }

    private static String entityId(
            JsonNode root,
            String field,
            String claimedId
    ) {
        String value = requiredText(root, field, claimedId);
        if (!ENTITY_ID.matcher(value).matches()) {
            throw invalid(
                    "INVALID_SCHEMA", field + " has an invalid identifier.", claimedId);
        }
        return value;
    }

    private static String requiredText(
            JsonNode root,
            String field,
            String claimedId
    ) {
        JsonNode value = root.get(field);
        if (!value.isTextual() || value.textValue().isBlank()
                || !value.textValue().equals(value.textValue().trim())) {
            throw invalid(
                    "INVALID_SCHEMA", field + " must be a non-blank exact string.", claimedId);
        }
        return value.textValue();
    }

    private static String nullableNonBlankText(
            JsonNode root,
            String field,
            String claimedId
    ) {
        JsonNode value = root.get(field);
        if (value.isNull()) {
            return null;
        }
        return requiredText(root, field, claimedId);
    }

    private static int requiredInteger(
            JsonNode root,
            String field,
            String claimedId
    ) {
        JsonNode value = root.get(field);
        if (!value.isIntegralNumber() || !value.canConvertToInt()) {
            throw invalid(
                    "INVALID_SCHEMA", field + " must be an integer.", claimedId);
        }
        return value.intValue();
    }

    private static long requiredLong(
            JsonNode root,
            String field,
            String claimedId
    ) {
        JsonNode value = root.get(field);
        if (!value.isIntegralNumber() || !value.canConvertToLong()) {
            throw invalid(
                    "INVALID_SCHEMA", field + " must be an integer.", claimedId);
        }
        return value.longValue();
    }

    private static long requiredNonNegativeLong(
            JsonNode root,
            String field,
            String claimedId
    ) {
        long result = requiredLong(root, field, claimedId);
        if (result < 0) {
            throw invalid(
                    "INVALID_EVIDENCE", field + " must not be negative.", claimedId);
        }
        return result;
    }

    private static void requireExactFields(
            JsonNode root,
            Set<String> expected,
            String label,
            String claimedId
    ) {
        List<String> actual = new ArrayList<>();
        root.fieldNames().forEachRemaining(actual::add);
        if (actual.size() != expected.size()
                || !Set.copyOf(actual).equals(expected)) {
            throw invalid(
                    "INVALID_SCHEMA",
                    "The " + label + " fields do not exactly match schemaVersion 1.",
                    claimedId);
        }
    }

    private static String optionalText(JsonNode root, String field) {
        JsonNode value = root.get(field);
        return value != null && value.isTextual() ? value.textValue() : null;
    }

    static String sha256(String value) {
        return sha256(value.getBytes(StandardCharsets.UTF_8));
    }

    static String sha256(byte[] value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256")
                            .digest(value));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable.", impossible);
        }
    }

    private static SignalProposalValidationException invalid(
            String code,
            String detail
    ) {
        return new SignalProposalValidationException(code, detail);
    }

    private static SignalProposalValidationException invalid(
            String code,
            String detail,
            String claimedId
    ) {
        return new SignalProposalValidationException(code, detail, claimedId);
    }

    private record Evidence(
            long eventCount,
            long dataQualityEvents,
            BigDecimal windowDemand,
            BigDecimal gross,
            BigDecimal usable,
            BigDecimal safety,
            BigDecimal shortage,
            BigDecimal expiry
    ) {
        String exactJson() {
            return "{\"eventCount\":" + eventCount
                    + ",\"dataQualityEvents\":" + dataQualityEvents
                    + ",\"windowDemand\":\"" + windowDemand.toPlainString()
                    + "\",\"snapshotGrossDemand\":" + decimalOrNull(gross)
                    + ",\"snapshotUsableSupply\":" + decimalOrNull(usable)
                    + ",\"snapshotSafetyStock\":" + decimalOrNull(safety)
                    + ",\"snapshotShortage\":" + decimalOrNull(shortage)
                    + ",\"snapshotExpiryRiskSurplus\":" + decimalOrNull(expiry)
                    + "}";
        }

        private static String decimalOrNull(BigDecimal value) {
            return value == null ? "null" : "\"" + value.toPlainString() + "\"";
        }
    }
}
