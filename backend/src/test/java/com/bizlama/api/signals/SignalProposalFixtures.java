package com.bizlama.api.signals;

import java.time.Instant;
import java.util.Map;

final class SignalProposalFixtures {

    static final long WINDOW_START = 1_788_955_200_000L;
    static final long WINDOW_END = WINDOW_START + 3_600_000L;
    static final String PROJECT = "bizlm-prod";
    static final String SUBSCRIPTION = "bizlama-governed-proposals-worker";
    static final String TARGET =
            "projects/bizlm-prod/topics/bizlama-governed-proposals";
    static final String EVIDENCE = "{\"eventCount\":2,"
            + "\"dataQualityEvents\":0,"
            + "\"windowDemand\":\"300\","
            + "\"snapshotGrossDemand\":\"300\","
            + "\"snapshotUsableSupply\":\"100\","
            + "\"snapshotSafetyStock\":\"0\","
            + "\"snapshotShortage\":\"200\","
            + "\"snapshotExpiryRiskSurplus\":\"0\"}";

    private SignalProposalFixtures() {
    }

    static Fixture shortage(String kitchenId, String ingredientId) {
        return shortage(kitchenId, "location-main", ingredientId);
    }

    static Fixture shortage(
            String kitchenId,
            String locationId,
            String ingredientId
    ) {
        String seed = kitchenId + "|" + locationId + "|" + ingredientId
                + "|1h|" + WINDOW_START + "|SHORTAGE_RISK";
        String proposalId = "SIG-" + SignalProposalParser.sha256(seed)
                .substring(0, 24);
        String json = """
                {"proposalId":"%s","schemaVersion":1,
                "commandType":"CREATE_GOVERNED_RECOMMENDATION_PROPOSAL",
                "signalType":"SHORTAGE_RISK","riskTier":"HIGH",
                "kitchenId":"%s","locationId":"%s","ingredientId":"%s",
                "windowName":"1h",
                "windowStartMillis":%d,"windowEndMillis":%d,
                "proposedQuantity":2E+2,"canonicalUnit":"g",
                "reasonCode":"AUTHORITATIVE_DEMAND_SNAPSHOT_SHORTAGE",
                "evidenceJson":"%s","targetQueue":"%s",
                "directMutationAllowed":false}
                """.formatted(
                proposalId,
                kitchenId,
                locationId,
                ingredientId,
                WINDOW_START,
                WINDOW_END,
                EVIDENCE.replace("\"", "\\\""),
                TARGET).replace("\n", "");
        return new Fixture(
                proposalId,
                json.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                Map.of(
                        "proposalId", proposalId,
                        "signalTime", Instant.ofEpochMilli(WINDOW_END).toString(),
                        "schemaVersion", "1",
                        "commandType", SignalProposalParser.COMMAND_TYPE,
                        "kitchenId", kitchenId,
                        "locationId", locationId));
    }

    record Fixture(
            String proposalId,
            byte[] body,
            Map<String, String> attributes
    ) {
    }
}
