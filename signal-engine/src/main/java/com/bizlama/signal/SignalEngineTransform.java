package com.bizlama.signal;

import com.bizlama.signal.EventModels.CanonicalEvent;
import com.bizlama.signal.EventModels.BrokerEvent;
import com.bizlama.signal.EventModels.ErrorRecord;
import com.bizlama.signal.EventModels.FactRecord;
import com.bizlama.signal.EventModels.FeatureMetrics;
import com.bizlama.signal.EventModels.FeatureRecord;
import com.bizlama.signal.EventModels.ParseOutcome;
import com.bizlama.signal.EventModels.ProposalCommand;
import com.bizlama.signal.EventModels.SignalType;
import java.io.Serializable;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import org.apache.beam.sdk.coders.SerializableCoder;
import org.apache.beam.sdk.coders.StringUtf8Coder;
import org.apache.beam.sdk.metrics.Counter;
import org.apache.beam.sdk.metrics.Metrics;
import org.apache.beam.sdk.state.TimeDomain;
import org.apache.beam.sdk.transforms.Combine;
import org.apache.beam.sdk.transforms.Deduplicate;
import org.apache.beam.sdk.transforms.DoFn;
import org.apache.beam.sdk.transforms.Flatten;
import org.apache.beam.sdk.transforms.PTransform;
import org.apache.beam.sdk.transforms.ParDo;
import org.apache.beam.sdk.transforms.SerializableBiFunction;
import org.apache.beam.sdk.transforms.SerializableFunction;
import org.apache.beam.sdk.transforms.WithKeys;
import org.apache.beam.sdk.transforms.Values;
import org.apache.beam.sdk.values.KV;
import org.apache.beam.sdk.values.PCollection;
import org.apache.beam.sdk.values.PCollectionList;
import org.apache.beam.sdk.values.PCollectionTuple;
import org.apache.beam.sdk.values.TupleTag;
import org.apache.beam.sdk.values.TupleTagList;
import org.apache.beam.sdk.transforms.windowing.BoundedWindow;
import org.apache.beam.sdk.transforms.windowing.FixedWindows;
import org.apache.beam.sdk.transforms.windowing.GlobalWindows;
import org.apache.beam.sdk.transforms.windowing.IntervalWindow;
import org.apache.beam.sdk.transforms.windowing.Window;
import org.apache.beam.sdk.transforms.windowing.Window.ClosingBehavior;
import org.joda.time.Instant;

public final class SignalEngineTransform
        extends PTransform<PCollection<BrokerEvent>, PCollectionTuple> {

    public static final TupleTag<CanonicalEvent> RAW_VALID =
            new TupleTag<>() { };
    public static final TupleTag<FactRecord> FACTS =
            new TupleTag<>() { };
    public static final TupleTag<FeatureRecord> FEATURES =
            new TupleTag<>() { };
    public static final TupleTag<ErrorRecord> ERRORS =
            new TupleTag<>() { };
    public static final TupleTag<ProposalCommand> PROPOSALS =
            new TupleTag<>() { };

    private static final TupleTag<CanonicalEvent> PROCESSABLE =
            new TupleTag<>() { };
    private static final String KEY_SEPARATOR = "\u001f";

    private final Config config;

    public SignalEngineTransform(Config config) {
        this.config = config.validated();
    }

    @Override
    public PCollectionTuple expand(PCollection<BrokerEvent> input) {
        PCollectionTuple parsed = input.apply(
                "Parse and validate envelopes",
                ParDo.of(new ParseAndValidateFn(
                        config.allowedLateness(), config.requireBrokerAttributes()))
                        .withOutputTags(
                                PROCESSABLE,
                                TupleTagList.of(RAW_VALID).and(ERRORS)
                        )
        );
        parsed.get(PROCESSABLE).setCoder(
                SerializableCoder.of(CanonicalEvent.class));
        parsed.get(RAW_VALID).setCoder(
                SerializableCoder.of(CanonicalEvent.class));
        parsed.get(ERRORS).setCoder(SerializableCoder.of(ErrorRecord.class));

        PCollection<CanonicalEvent> unique;
        if (config.reconciliationMode()) {
            PCollection<KV<String, CanonicalEvent>> keyedByEventId =
                    parsed.get(PROCESSABLE).apply(
                            "Key exact replay by event ID",
                            WithKeys.of(CanonicalEvent::eventId));
            keyedByEventId.setCoder(org.apache.beam.sdk.coders.KvCoder.of(
                    StringUtf8Coder.of(),
                    SerializableCoder.of(CanonicalEvent.class)));
            unique = keyedByEventId
                    .apply(
                            "Choose deterministic exact replay representative",
                            Combine.perKey(
                                    (SerializableBiFunction<CanonicalEvent,
                                            CanonicalEvent, CanonicalEvent>)
                                            SignalEngineTransform::earliestRecord))
                    .apply("Drop exact replay event-ID keys", Values.create());
        } else {
            unique = parsed.get(PROCESSABLE).apply(
                        "Bounded online event-ID deduplication",
                        Deduplicate.withRepresentativeValueFn(
                                        (SerializableFunction<CanonicalEvent, String>)
                                                CanonicalEvent::eventId)
                                .withRepresentativeCoder(StringUtf8Coder.of())
                                .withDuration(org.joda.time.Duration.millis(
                                        config.deduplicationHorizon().toMillis()))
                                .withTimeDomain(TimeDomain.EVENT_TIME)
                );
        }
        unique.setCoder(SerializableCoder.of(CanonicalEvent.class));

        PCollection<FactRecord> facts = unique.apply(
                "Create immutable facts", ParDo.of(new ToFactFn()));
        facts.setCoder(SerializableCoder.of(FactRecord.class));

        PCollection<FeatureRecord> fifteenMinutes = buildConfiguredFeatures(
                unique, "15m", org.joda.time.Duration.standardMinutes(15));
        PCollection<FeatureRecord> hourly = buildConfiguredFeatures(
                unique, "1h", org.joda.time.Duration.standardHours(1));
        PCollection<FeatureRecord> daily = buildConfiguredFeatures(
                unique, "daily", org.joda.time.Duration.standardDays(1));
        PCollection<FeatureRecord> features = PCollectionList
                .of(fifteenMinutes)
                .and(hourly)
                .and(daily)
                .apply("Merge feature horizons", Flatten.pCollections());
        features.setCoder(SerializableCoder.of(FeatureRecord.class));

        PCollection<ProposalCommand> proposals = features.apply(
                "Create governed proposal commands",
                ParDo.of(new DeterministicSignalFn(
                        config.governedProposalTarget()))
        );
        proposals.setCoder(SerializableCoder.of(ProposalCommand.class));

        return PCollectionTuple.of(RAW_VALID, parsed.get(RAW_VALID))
                .and(FACTS, facts)
                .and(FEATURES, features)
                .and(ERRORS, parsed.get(ERRORS))
                .and(PROPOSALS, proposals);
    }

    private static CanonicalEvent earliestRecord(
            CanonicalEvent left,
            CanonicalEvent right
    ) {
        int recordedOrder = Long.compare(
                left.recordedAtMillis(), right.recordedAtMillis());
        if (recordedOrder != 0) {
            return recordedOrder < 0 ? left : right;
        }
        return left.rawJson().compareTo(right.rawJson()) <= 0 ? left : right;
    }

    private PCollection<FeatureRecord> buildConfiguredFeatures(
            PCollection<CanonicalEvent> input,
            String windowName,
            org.joda.time.Duration windowSize
    ) {
        return config.reconciliationMode()
                ? buildReplayFeatures(input, windowName, windowSize)
                : buildFeatures(input, windowName, windowSize);
    }

    private PCollection<FeatureRecord> buildReplayFeatures(
            PCollection<CanonicalEvent> input,
            String windowName,
            org.joda.time.Duration windowSize
    ) {
        long sizeMillis = windowSize.getMillis();
        PCollection<KV<String, CanonicalEvent>> keyed = input.apply(
                "Key replay " + windowName + " by event-time bucket and scope",
                WithKeys.of((CanonicalEvent event) -> {
                    long start = Math.floorDiv(event.eventTimeMillis(), sizeMillis)
                            * sizeMillis;
                    return event.kitchenId() + KEY_SEPARATOR
                            + event.locationId() + KEY_SEPARATOR
                            + event.ingredientId() + KEY_SEPARATOR + start;
                })
        );
        keyed.setCoder(org.apache.beam.sdk.coders.KvCoder.of(
                StringUtf8Coder.of(), SerializableCoder.of(CanonicalEvent.class)));
        return keyed.apply(
                        "Aggregate replay " + windowName + " features",
                        Combine.perKey(new FeatureCombineFn()))
                .apply(
                        "Materialize replay " + windowName + " feature rows",
                        ParDo.of(new ToReplayFeatureRecordFn(
                                windowName, sizeMillis)))
                .setCoder(SerializableCoder.of(FeatureRecord.class));
    }

    private PCollection<FeatureRecord> buildFeatures(
            PCollection<CanonicalEvent> input,
            String windowName,
            org.joda.time.Duration windowSize
    ) {
        PCollection<KV<String, CanonicalEvent>> keyed = input
                .apply(
                        "Window " + windowName,
                        Window.<CanonicalEvent>into(FixedWindows.of(windowSize))
                                .withAllowedLateness(
                                        org.joda.time.Duration.millis(
                                                config.allowedLateness().toMillis()),
                                        ClosingBehavior.FIRE_ALWAYS)
                                .accumulatingFiredPanes()
                )
                .apply(
                        "Key " + windowName
                                + " by kitchen, location, and ingredient",
                        WithKeys.of((CanonicalEvent event) ->
                                event.kitchenId() + KEY_SEPARATOR
                                        + event.locationId() + KEY_SEPARATOR
                                        + event.ingredientId())
                );
        keyed.setCoder(org.apache.beam.sdk.coders.KvCoder.of(
                StringUtf8Coder.of(),
                SerializableCoder.of(CanonicalEvent.class)
        ));

        PCollection<KV<String, FeatureMetrics>> combined = keyed.apply(
                "Aggregate " + windowName + " features",
                Combine.perKey(new FeatureCombineFn())
        );
        combined.setCoder(org.apache.beam.sdk.coders.KvCoder.of(
                StringUtf8Coder.of(),
                SerializableCoder.of(FeatureMetrics.class)
        ));
        PCollection<FeatureRecord> materialized = combined.apply(
                "Materialize " + windowName + " feature rows",
                ParDo.of(new ToFeatureRecordFn(windowName))
        ).setCoder(SerializableCoder.of(FeatureRecord.class));
        return materialized.apply(
                "Globalize " + windowName + " materialized rows",
                Window.into(new GlobalWindows())
        );
    }

    public record Config(
            Duration allowedLateness,
            Duration deduplicationHorizon,
            boolean reconciliationMode,
            String governedProposalTarget,
            boolean requireBrokerAttributes
    ) implements Serializable {
        public Config validated() {
            if (allowedLateness == null
                    || allowedLateness.isZero()
                    || allowedLateness.isNegative()) {
                throw new IllegalArgumentException(
                        "allowedLateness must be positive.");
            }
            if (deduplicationHorizon == null
                    || deduplicationHorizon.isZero()
                    || deduplicationHorizon.isNegative()) {
                throw new IllegalArgumentException(
                        "deduplicationHorizon must be positive.");
            }
            if (governedProposalTarget == null
                    || governedProposalTarget.isBlank()) {
                throw new IllegalArgumentException(
                        "governedProposalTarget must be non-blank.");
            }
            return this;
        }

        public static Config streamingDefaults() {
            return new Config(
                    Duration.ofHours(2),
                    Duration.ofHours(24),
                    false,
                    "pubsub://governed-proposal-queue",
                    true
            );
        }

        public static Config reconciliationDefaults() {
            return new Config(
                    Duration.ofDays(36500),
                    Duration.ofHours(24),
                    true,
                    "log://synthetic-governed-proposals",
                    false
            );
        }
    }

    private static final class ToReplayFeatureRecordFn
            extends DoFn<KV<String, FeatureMetrics>, FeatureRecord> {

        private final String windowName;
        private final long windowSizeMillis;

        private ToReplayFeatureRecordFn(String windowName, long windowSizeMillis) {
            this.windowName = windowName;
            this.windowSizeMillis = windowSizeMillis;
        }

        @ProcessElement
        public void process(ProcessContext context) {
            String[] scope = context.element().getKey()
                    .split(KEY_SEPARATOR, 4);
            long start = Long.parseLong(scope[3]);
            FeatureMetrics value = context.element().getValue();
            context.output(new FeatureRecord(
                    windowName,
                    start,
                    start + windowSizeMillis,
                    0,
                    "ON_TIME",
                    true,
                    scope[0],
                    scope[1],
                    scope[2],
                    value.demand(),
                    value.receipts(),
                    value.consumption(),
                    value.waste(),
                    value.expired(),
                    value.reversals(),
                    value.corrections(),
                    value.snapshotGrossDemand(),
                    value.snapshotUsableSupply(),
                    value.snapshotSafetyStock(),
                    value.snapshotShortage(),
                    value.snapshotExpiryRiskSurplus(),
                    value.stockouts(),
                    value.recommendationDecisions(),
                    value.recommendationOutcomes(),
                    value.dataQualityEvents(),
                    value.eventCount(),
                    value.canonicalUnit()
            ));
        }
    }

    private static final class ParseAndValidateFn
            extends DoFn<BrokerEvent, CanonicalEvent> {

        private static final Counter INVALID_EVENTS = Metrics.counter(
                ParseAndValidateFn.class, "InvalidEvents");

        private final Duration allowedLateness;
        private final boolean requireBrokerAttributes;
        private transient EventParser parser;

        private ParseAndValidateFn(
                Duration allowedLateness,
                boolean requireBrokerAttributes
        ) {
            this.allowedLateness = allowedLateness;
            this.requireBrokerAttributes = requireBrokerAttributes;
        }

        @Setup
        public void setup() {
            parser = new EventParser();
        }

        @Override
        @SuppressWarnings("deprecation")
        public org.joda.time.Duration getAllowedTimestampSkew() {
            // Pub/Sub publish time is deliberately not trusted as event time.
            // The authenticated body/attribute pair is validated first, then the
            // canonical occurredAt value is assigned here, including for replay.
            return org.joda.time.Duration.millis(Long.MAX_VALUE);
        }

        @ProcessElement
        public void process(ProcessContext context) {
            ParseOutcome result = parser.parse(
                    context.element(),
                    allowedLateness,
                    requireBrokerAttributes
            );
            if (result.retainRaw()) {
                context.outputWithTimestamp(
                        RAW_VALID,
                        result.event(),
                        new Instant(result.event().eventTimeMillis())
                );
            }
            if (result.error() != null) {
                INVALID_EVENTS.inc();
                context.output(ERRORS, result.error());
            }
            if (result.process()) {
                context.outputWithTimestamp(
                        result.event(),
                        new Instant(result.event().eventTimeMillis())
                );
            }
        }
    }

    private static final class ToFactFn
            extends DoFn<CanonicalEvent, FactRecord> {

        @ProcessElement
        public void process(ProcessContext context) {
            CanonicalEvent event = context.element();
            context.output(new FactRecord(
                    event.schemaVersion(),
                    event.eventId(),
                    event.eventType(),
                    event.kitchenId(),
                    event.locationId(),
                    event.entityType(),
                    event.entityId(),
                    event.occurredAtMillis(),
                    event.recordedAtMillis(),
                    event.correlationId(),
                    event.causationId(),
                    event.deduplicationKey(),
                    event.payloadJson(),
                    event.sourceMetadataJson(),
                    event.ingredientId(),
                    event.sourceQuantity(),
                    event.sourceUnit(),
                    event.canonicalQuantity(),
                    event.canonicalUnit(),
                    event.dimension().name(),
                    event.snapshotGrossDemand(),
                    event.snapshotUsableSupply(),
                    event.snapshotSafetyStock(),
                    event.snapshotShortage(),
                    event.snapshotExpiryRiskSurplus(),
                    event.qualityFlags()
            ));
        }
    }

    private static final class ToFeatureRecordFn
            extends DoFn<KV<String, FeatureMetrics>, FeatureRecord> {

        private final String windowName;

        private ToFeatureRecordFn(String windowName) {
            this.windowName = windowName;
        }

        @ProcessElement
        public void process(
                ProcessContext context,
                BoundedWindow boundedWindow
        ) {
            IntervalWindow window = (IntervalWindow) boundedWindow;
            String[] scope = context.element().getKey()
                    .split(KEY_SEPARATOR, 3);
            FeatureMetrics value = context.element().getValue();
            context.output(new FeatureRecord(
                    windowName,
                    window.start().getMillis(),
                    window.end().getMillis(),
                    context.pane().getIndex(),
                    context.pane().getTiming().name(),
                    context.pane().isLast(),
                    scope[0],
                    scope[1],
                    scope[2],
                    value.demand(),
                    value.receipts(),
                    value.consumption(),
                    value.waste(),
                    value.expired(),
                    value.reversals(),
                    value.corrections(),
                    value.snapshotGrossDemand(),
                    value.snapshotUsableSupply(),
                    value.snapshotSafetyStock(),
                    value.snapshotShortage(),
                    value.snapshotExpiryRiskSurplus(),
                    value.stockouts(),
                    value.recommendationDecisions(),
                    value.recommendationOutcomes(),
                    value.dataQualityEvents(),
                    value.eventCount(),
                    value.canonicalUnit()
            ));
        }
    }

    private static final class DeterministicSignalFn
            extends DoFn<FeatureRecord, ProposalCommand> {

        private final String targetQueue;

        private DeterministicSignalFn(String targetQueue) {
            this.targetQueue = targetQueue;
        }

        @ProcessElement
        public void process(ProcessContext context) {
            FeatureRecord feature = context.element();
            // Late panes remain append-only analytical revisions. Emitting a
            // second operational proposal for the same governed window would
            // create ambiguous operator work from revised evidence.
            if (!"ON_TIME".equals(feature.paneTiming())) {
                return;
            }
            if (feature.snapshotShortage() != null
                    && feature.snapshotShortage().signum() > 0) {
                context.output(proposal(
                        feature,
                        SignalType.SHORTAGE_RISK,
                        feature.snapshotShortage(),
                        "AUTHORITATIVE_DEMAND_SNAPSHOT_SHORTAGE"
                ));
            }
            if (feature.snapshotExpiryRiskSurplus() != null
                    && feature.snapshotExpiryRiskSurplus().signum() > 0) {
                BigDecimal surplus = feature.snapshotExpiryRiskSurplus();
                context.output(proposal(
                        feature,
                        SignalType.EXPIRY_RISK_SURPLUS,
                        surplus,
                        "AUTHORITATIVE_SNAPSHOT_EXPIRY_RISK_SURPLUS"
                ));
            }
            boolean materialQuality = feature.dataQualityEvents() >= 3
                    || (feature.eventCount() >= 2
                    && feature.dataQualityEvents() * 4 >= feature.eventCount());
            if (materialQuality) {
                context.output(proposal(
                        feature,
                        SignalType.MATERIAL_DATA_QUALITY,
                        null,
                        "MATERIAL_CANONICAL_DATA_QUALITY_RATE"
                ));
            }
        }

        private ProposalCommand proposal(
                FeatureRecord feature,
                SignalType type,
                BigDecimal quantity,
                String reasonCode
        ) {
            String evidence = "{"
                    + "\"eventCount\":" + feature.eventCount() + ","
                    + "\"dataQualityEvents\":"
                    + feature.dataQualityEvents() + ","
                    + "\"windowDemand\":\"" + feature.demand().toPlainString()
                    + "\",\"snapshotGrossDemand\":"
                    + nullableDecimal(feature.snapshotGrossDemand()) + ","
                    + "\"snapshotUsableSupply\":"
                    + nullableDecimal(feature.snapshotUsableSupply()) + ","
                    + "\"snapshotSafetyStock\":"
                    + nullableDecimal(feature.snapshotSafetyStock()) + ","
                    + "\"snapshotShortage\":"
                    + nullableDecimal(feature.snapshotShortage()) + ","
                    + "\"snapshotExpiryRiskSurplus\":"
                    + nullableDecimal(feature.snapshotExpiryRiskSurplus())
                    + "}";
            String seed = feature.kitchenId() + "|" + feature.locationId()
                    + "|" + feature.ingredientId() + "|"
                    + feature.windowName() + "|"
                    + feature.windowStartMillis() + "|" + type.name();
            return new ProposalCommand(
                    "SIG-" + sha256(seed).substring(0, 24),
                    1,
                    "CREATE_GOVERNED_RECOMMENDATION_PROPOSAL",
                    type,
                    "HIGH",
                    feature.kitchenId(),
                    feature.locationId(),
                    feature.ingredientId(),
                    feature.windowName(),
                    feature.windowStartMillis(),
                    feature.windowEndMillis(),
                    quantity,
                    feature.canonicalUnit(),
                    reasonCode,
                    evidence,
                    targetQueue,
                    false
            );
        }

        private String nullableDecimal(BigDecimal value) {
            return value == null
                    ? "null"
                    : "\"" + value.toPlainString() + "\"";
        }

        private String sha256(String value) {
            try {
                return HexFormat.of().formatHex(
                        MessageDigest.getInstance("SHA-256")
                                .digest(value.getBytes(StandardCharsets.UTF_8))
                );
            } catch (NoSuchAlgorithmException impossible) {
                throw new IllegalStateException(
                        "SHA-256 is unavailable.", impossible);
            }
        }
    }
}
