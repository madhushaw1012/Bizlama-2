package com.bizlama.signal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.bizlama.signal.EventModels.BrokerEvent;
import com.bizlama.signal.EventModels.FactRecord;
import com.bizlama.signal.EventModels.FeatureRecord;
import com.bizlama.signal.EventModels.ProposalCommand;
import com.bizlama.signal.EventModels.SignalType;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.beam.runners.direct.DirectRunner;
import org.apache.beam.sdk.Pipeline;
import org.apache.beam.sdk.PipelineResult;
import org.apache.beam.sdk.coders.SerializableCoder;
import org.apache.beam.sdk.metrics.MetricsFilter;
import org.apache.beam.sdk.options.PipelineOptions;
import org.apache.beam.sdk.options.PipelineOptionsFactory;
import org.apache.beam.sdk.testing.PAssert;
import org.apache.beam.sdk.testing.TestStream;
import org.apache.beam.sdk.transforms.Create;
import org.apache.beam.sdk.transforms.Filter;
import org.apache.beam.sdk.values.PCollectionTuple;
import org.apache.beam.sdk.values.TimestampedValue;
import org.joda.time.Instant;
import org.junit.jupiter.api.Test;

class SignalEngineTransformTest {

    @Test
    void duplicateOutOfOrderAndMultipleKitchensStayIsolated() {
        Pipeline pipeline = directPipeline();
        BrokerEvent demandA = replay(analytics(
                "a-demand", "ORDER_INGREDIENT_DEMAND", "kitchen-a", "rice",
                "2026-09-09T10:10:00Z", "2026-09-09T10:11:00Z",
                "{\"ingredientId\":\"rice\",\"canonicalDemand\":1,"
                        + "\"canonicalUnit\":\"kg\"}"));
        PCollectionTuple output = pipeline.apply(Create.of(
                        demandA,
                        replay(analytics("a-receipt", "INVENTORY_PURCHASED",
                                "kitchen-a", "rice", "2026-09-09T10:05:00Z",
                                "2026-09-09T10:06:00Z",
                                "{\"ingredientId\":\"rice\",\"quantity\":250,"
                                        + "\"unit\":\"g\"}")),
                        demandA,
                        replay(analytics("b-demand", "ORDER_INGREDIENT_DEMAND",
                                "kitchen-b", "rice", "2026-09-09T10:07:00Z",
                                "2026-09-09T10:08:00Z",
                                "{\"ingredientId\":\"rice\",\"canonicalDemand\":2,"
                                        + "\"canonicalUnit\":\"kg\"}")),
                        replay(analytics("accepted", "ORDER_ACCEPTED", "kitchen-a",
                                "order-1", "2026-09-09T10:02:00Z",
                                "2026-09-09T10:02:01Z", "{\"total\":14.25}"))))
                .apply(new SignalEngineTransform(
                        SignalEngineTransform.Config.reconciliationDefaults()));

        PAssert.that(output.get(SignalEngineTransform.RAW_VALID))
                .satisfies(values -> { assertEquals(5, size(values)); return null; });
        PAssert.that(output.get(SignalEngineTransform.FACTS))
                .satisfies(values -> { assertEquals(3, size(values)); return null; });
        PAssert.that(output.get(SignalEngineTransform.FEATURES)
                        .apply(Filter.by(feature -> feature.windowName().equals("1h"))))
                .satisfies(values -> {
                    Map<String, FeatureRecord> byKitchen = new HashMap<>();
                    values.forEach(value -> byKitchen.put(value.kitchenId(), value));
                    assertEquals(Set.of("kitchen-a", "kitchen-b"), byKitchen.keySet());
                    assertDecimal("1000", byKitchen.get("kitchen-a").demand());
                    assertDecimal("250", byKitchen.get("kitchen-a").receipts());
                    assertDecimal("2000", byKitchen.get("kitchen-b").demand());
                    return null;
                });
        pipeline.run().waitUntilFinish();
    }

    @Test
    void sameKitchenLocationsAndStockoutOutcomesStayIsolated() {
        Pipeline pipeline = directPipeline();
        PCollectionTuple output = pipeline.apply(Create.of(
                        replay(analytics("north-demand",
                                "ORDER_INGREDIENT_DEMAND",
                                "kitchen-a", "rice",
                                "2026-09-09T10:01:00Z",
                                "2026-09-09T10:02:00Z",
                                "{\"locationId\":\"location-north\","
                                        + "\"ingredientId\":\"rice\","
                                        + "\"canonicalDemand\":100,"
                                        + "\"canonicalUnit\":\"g\"}")),
                        replay(analytics("south-demand",
                                "ORDER_INGREDIENT_DEMAND",
                                "kitchen-a", "rice",
                                "2026-09-09T10:03:00Z",
                                "2026-09-09T10:04:00Z",
                                "{\"locationId\":\"location-south\","
                                        + "\"ingredientId\":\"rice\","
                                        + "\"canonicalDemand\":200,"
                                        + "\"canonicalUnit\":\"g\"}")),
                        replay(analytics("north-stockout",
                                "RECOMMENDATION_OUTCOME_RECORDED",
                                "kitchen-a", "outcome-north",
                                "2026-09-09T10:05:00Z",
                                "2026-09-09T10:06:00Z",
                                "{\"locationId\":\"location-north\","
                                        + "\"ingredientId\":\"rice\","
                                        + "\"outcomeType\":\"STOCKOUT\","
                                        + "\"quantity\":1,\"unit\":\"g\"}")),
                        replay(analytics("south-fulfilled",
                                "RECOMMENDATION_OUTCOME_RECORDED",
                                "kitchen-a", "outcome-south",
                                "2026-09-09T10:07:00Z",
                                "2026-09-09T10:08:00Z",
                                "{\"locationId\":\"location-south\","
                                        + "\"ingredientId\":\"rice\","
                                        + "\"outcomeType\":\"FULFILLED\","
                                        + "\"quantity\":1,\"unit\":\"g\"}"))))
                .apply(new SignalEngineTransform(
                        SignalEngineTransform.Config.reconciliationDefaults()));

        PAssert.that(output.get(SignalEngineTransform.FEATURES)
                        .apply(Filter.by(feature -> feature.windowName().equals("1h"))))
                .satisfies(values -> {
                    Map<String, FeatureRecord> byLocation = new HashMap<>();
                    values.forEach(value -> byLocation.put(value.locationId(), value));
                    assertEquals(Set.of("location-north", "location-south"),
                            byLocation.keySet());
                    assertDecimal("100", byLocation.get("location-north").demand());
                    assertDecimal("200", byLocation.get("location-south").demand());
                    assertEquals(1, byLocation.get("location-north").stockouts());
                    assertEquals(0, byLocation.get("location-south").stockouts());
                    assertEquals(1,
                            byLocation.get("location-north").recommendationOutcomes());
                    assertEquals(1,
                            byLocation.get("location-south").recommendationOutcomes());
                    return null;
                });
        pipeline.run().waitUntilFinish();
    }

    @Test
    void invalidEnvelopeIncrementsObservableRunnerCounter() {
        Pipeline pipeline = directPipeline();
        PCollectionTuple output = pipeline
                .apply(Create.of(BrokerEvent.replay("{not-json")))
                .apply(new SignalEngineTransform(
                        SignalEngineTransform.Config.reconciliationDefaults()));

        PAssert.that(output.get(SignalEngineTransform.ERRORS))
                .satisfies(values -> {
                    assertEquals(1, size(values));
                    values.forEach(error ->
                            assertTrue(error.observedAtMillis() > 0));
                    return null;
                });
        PipelineResult result = pipeline.run();
        result.waitUntilFinish();

        long invalid = 0;
        for (var metric : result.metrics().queryMetrics(
                MetricsFilter.builder().build()).getCounters()) {
            if (metric.getName().getName().equals("InvalidEvents")) {
                invalid += metric.getAttempted();
            }
        }
        assertEquals(1, invalid);
    }

    @Test
    void watermarkSeparatesOutOfOrderFromActuallyTooLateData() {
        Pipeline pipeline = directPipeline();
        BrokerEvent first = analytics("wm-1", "ORDER_INGREDIENT_DEMAND",
                "kitchen-a", "rice", "2026-09-09T10:05:00Z",
                "2026-09-09T10:06:00Z",
                "{\"ingredientId\":\"rice\",\"canonicalDemand\":100,"
                        + "\"canonicalUnit\":\"g\"}");
        BrokerEvent outOfOrder = analytics("wm-2", "ORDER_INGREDIENT_DEMAND",
                "kitchen-a", "rice", "2026-09-09T10:03:00Z",
                "2026-09-09T14:03:00Z",
                "{\"ingredientId\":\"rice\",\"canonicalDemand\":200,"
                        + "\"canonicalUnit\":\"g\"}");
        BrokerEvent late = analytics("wm-late", "ORDER_INGREDIENT_DEMAND",
                "kitchen-a", "rice", "2026-09-09T10:04:00Z",
                "2026-09-09T10:05:00Z",
                "{\"ingredientId\":\"rice\",\"canonicalDemand\":400,"
                        + "\"canonicalUnit\":\"g\"}");
        TestStream<BrokerEvent> stream = TestStream
                .create(SerializableCoder.of(BrokerEvent.class))
                .advanceWatermarkTo(Instant.parse("2026-09-09T10:00:00Z"))
                .addElements(TimestampedValue.of(first,
                        Instant.parse("2026-09-09T10:05:00Z")))
                .addElements(TimestampedValue.of(outOfOrder,
                        Instant.parse("2026-09-09T10:03:00Z")))
                .advanceWatermarkTo(Instant.parse("2026-09-09T13:01:00Z"))
                .addElements(TimestampedValue.of(late,
                        Instant.parse("2026-09-09T10:04:00Z")))
                .advanceWatermarkToInfinity();
        PCollectionTuple output = pipeline.apply(stream)
                .apply(new SignalEngineTransform(
                        SignalEngineTransform.Config.streamingDefaults()));

        PAssert.that(output.get(SignalEngineTransform.RAW_VALID))
                .satisfies(values -> { assertEquals(3, size(values)); return null; });
        PAssert.that(output.get(SignalEngineTransform.FACTS))
                .satisfies(values -> { assertEquals(3, size(values)); return null; });
        PAssert.that(output.get(SignalEngineTransform.FEATURES)
                        .apply(Filter.by(feature -> feature.windowName().equals("1h"))))
                .satisfies(values -> {
                    int count = 0;
                    for (FeatureRecord feature : values) {
                        assertDecimal("300", feature.demand());
                        count++;
                    }
                    assertTrue(count >= 1);
                    return null;
                });
        PipelineResult result = pipeline.run();
        result.waitUntilFinish();
        long dropped = 0;
        for (var metric : result.metrics().queryMetrics(
                MetricsFilter.builder().build()).getCounters()) {
            if (metric.getName().getName().equals("DroppedDueToLateness")) {
                dropped += metric.getAttempted();
            }
        }
        assertTrue(dropped >= 1, "runner late-data metric must be observable");
    }

    @Test
    void latestDemandSnapshotDrivesDeterministicGovernedQueueCommands() {
        Pipeline pipeline = directPipeline();
        PCollectionTuple output = pipeline.apply(Create.of(
                        replay(analytics("snapshot-old", "DEMAND_CALCULATION_SNAPSHOT",
                                "kitchen-a", "rice", "2026-09-09T10:01:00Z",
                                "2026-09-09T10:02:00Z",
                                "{\"ingredientId\":\"rice\",\"grossDemand\":100,"
                                        + "\"usableSupply\":100,\"safetyStock\":20,"
                                        + "\"shortage\":20,\"expiryRiskSurplus\":0,"
                                        + "\"canonicalUnit\":\"g\"}")),
                        replay(analytics("snapshot-new", "DEMAND_CALCULATION_SNAPSHOT",
                                "kitchen-a", "rice", "2026-09-09T10:05:00Z",
                                "2026-09-09T10:06:00Z",
                                "{\"ingredientId\":\"rice\",\"grossDemand\":300,"
                                        + "\"usableSupply\":100,\"safetyStock\":75,"
                                        + "\"shortage\":275,\"expiryRiskSurplus\":50,"
                                        + "\"canonicalUnit\":\"g\"}")),
                        replay(analytics("dq-1", "INVENTORY_PURCHASED", "kitchen-a",
                                "rice", "2026-09-09T10:07:00Z",
                                "2026-09-09T10:08:00Z",
                                "{\"ingredientId\":\"rice\",\"quantity\":2,"
                                        + "\"unit\":\"bucket\"}")),
                        replay(analytics("dq-2", "INVENTORY_CONSUMED", "kitchen-a",
                                "rice", "2026-09-09T10:09:00Z",
                                "2026-09-09T10:10:00Z",
                                "{\"ingredientId\":\"rice\",\"quantity\":3,"
                                        + "\"unit\":\"bucket\"}"))))
                .apply(new SignalEngineTransform(
                        SignalEngineTransform.Config.reconciliationDefaults()));

        PAssert.that(output.get(SignalEngineTransform.PROPOSALS)
                        .apply(Filter.by(proposal -> proposal.windowName().equals("1h"))))
                .satisfies(values -> {
                    List<ProposalCommand> proposals = copy(values);
                    assertEquals(3, proposals.size());
                    assertEquals(EnumSet.allOf(SignalType.class), proposals.stream()
                            .map(ProposalCommand::signalType)
                            .collect(java.util.stream.Collectors.toSet()));
                    ProposalCommand shortage = proposals.stream()
                            .filter(value -> value.signalType()
                                    == SignalType.SHORTAGE_RISK)
                            .findFirst()
                            .orElseThrow();
                    assertDecimal("275", shortage.proposedQuantity());
                    assertEquals("AUTHORITATIVE_DEMAND_SNAPSHOT_SHORTAGE",
                            shortage.reasonCode());
                    assertTrue(shortage.evidenceJson().contains(
                            "\"snapshotSafetyStock\":\"75\""));
                    Set<String> ids = new HashSet<>();
                    for (ProposalCommand proposal : proposals) {
                        assertTrue(ids.add(proposal.proposalId()));
                        assertEquals("log://synthetic-governed-proposals",
                                proposal.targetQueue());
                        assertFalse(proposal.directMutationAllowed());
                        assertEquals("HIGH", proposal.riskTier());
                    }
                    return null;
                });
        pipeline.run().waitUntilFinish();
    }

    @Test
    void actualInventoryAdjustmentTypesRemainSeparateExactFeatures() {
        Pipeline pipeline = directPipeline();
        List<BrokerEvent> events = new ArrayList<>();
        String[] types = {"INVENTORY_EXPIRED", "INVENTORY_REVERSAL",
                "INVENTORY_CORRECTED", "WASTE_RECORDED", "INVENTORY_CONSUMED"};
        for (int i = 0; i < types.length; i++) {
            events.add(replay(analytics("movement-" + i, types[i], "kitchen-a",
                    "rice", "2026-09-09T10:0" + i + ":00Z",
                    "2026-09-09T10:1" + i + ":00Z",
                    "{\"ingredientId\":\"rice\",\"quantity\":" + (i + 1)
                            + ",\"unit\":\"g\"}")));
        }
        PCollectionTuple output = pipeline.apply(Create.of(events))
                .apply(new SignalEngineTransform(
                        SignalEngineTransform.Config.reconciliationDefaults()));

        PAssert.that(output.get(SignalEngineTransform.FEATURES)
                        .apply(Filter.by(feature -> feature.windowName().equals("1h"))))
                .satisfies(values -> {
                    FeatureRecord feature = values.iterator().next();
                    assertDecimal("1", feature.expired());
                    assertDecimal("2", feature.reversals());
                    assertDecimal("3", feature.corrections());
                    assertDecimal("4", feature.waste());
                    assertDecimal("5", feature.consumption());
                    return null;
                });
        pipeline.run().waitUntilFinish();
    }

    @Test
    void exactReplaySelectsEarliestDuplicateAcrossWholeHistory() {
        Pipeline pipeline = directPipeline();
        BrokerEvent early = replay(analytics("historic", "ORDER_INGREDIENT_DEMAND",
                "kitchen-a", "rice", "2025-01-01T00:00:00Z",
                "2025-01-01T00:01:00Z",
                "{\"ingredientId\":\"rice\",\"canonicalDemand\":10,"
                        + "\"canonicalUnit\":\"g\"}"));
        BrokerEvent later = replay(analytics("historic", "ORDER_INGREDIENT_DEMAND",
                "kitchen-a", "rice", "2026-01-01T00:00:00Z",
                "2026-01-01T00:01:00Z",
                "{\"ingredientId\":\"rice\",\"canonicalDemand\":99,"
                        + "\"canonicalUnit\":\"g\"}"));
        PCollectionTuple output = pipeline.apply(Create.of(later, early))
                .apply(new SignalEngineTransform(
                        SignalEngineTransform.Config.reconciliationDefaults()));

        PAssert.that(output.get(SignalEngineTransform.RAW_VALID))
                .satisfies(values -> { assertEquals(2, size(values)); return null; });
        PAssert.that(output.get(SignalEngineTransform.FACTS))
                .satisfies(values -> {
                    List<FactRecord> facts = copy(values);
                    assertEquals(1, facts.size());
                    assertEquals(java.time.Instant.parse("2025-01-01T00:00:00Z")
                            .toEpochMilli(), facts.getFirst().occurredAtMillis());
                    assertDecimal("10", facts.getFirst().canonicalQuantity());
                    return null;
                });
        pipeline.run().waitUntilFinish();
    }

    private static BrokerEvent analytics(String... values) {
        return ProducerEnvelopeFixture.analytics(values[0], values[1], values[2],
                values[3], values[4], values[5], values[6]);
    }

    private static BrokerEvent replay(BrokerEvent event) {
        return ProducerEnvelopeFixture.replay(event);
    }

    private static Pipeline directPipeline() {
        PipelineOptions options = PipelineOptionsFactory.create();
        options.setRunner(DirectRunner.class);
        return Pipeline.create(options);
    }

    private static int size(Iterable<?> values) {
        int size = 0;
        for (Object ignored : values) size++;
        return size;
    }

    private static <T> List<T> copy(Iterable<T> values) {
        List<T> result = new ArrayList<>();
        values.forEach(result::add);
        return result;
    }

    private static void assertDecimal(String expected, BigDecimal actual) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual));
    }
}
