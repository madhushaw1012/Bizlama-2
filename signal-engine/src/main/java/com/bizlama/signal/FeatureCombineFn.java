package com.bizlama.signal;

import com.bizlama.signal.EventModels.AnalyticsType;
import com.bizlama.signal.EventModels.CanonicalEvent;
import com.bizlama.signal.EventModels.FeatureMetrics;
import java.io.Serializable;
import java.math.BigDecimal;
import java.util.Objects;
import org.apache.beam.sdk.transforms.Combine;

final class FeatureCombineFn extends
        Combine.CombineFn<CanonicalEvent, FeatureCombineFn.Accumulator, FeatureMetrics> {

    @Override
    public Accumulator createAccumulator() {
        return new Accumulator();
    }

    @Override
    public Accumulator addInput(Accumulator accumulator, CanonicalEvent event) {
        accumulator.eventCount++;
        if (!event.qualityFlags().isEmpty()) {
            accumulator.dataQualityEvents++;
        }
        if (event.canonicalUnit() != null) {
            if (accumulator.canonicalUnit == null) {
                accumulator.canonicalUnit = event.canonicalUnit();
            } else if (!accumulator.canonicalUnit.equals(event.canonicalUnit())) {
                accumulator.dataQualityEvents++;
                return accumulator;
            }
        }

        if (event.analyticsType() == AnalyticsType.DEMAND_CALCULATION_SNAPSHOT) {
            if (event.eventTimeMillis() >= accumulator.snapshotEventTime) {
                accumulator.snapshotGrossDemand = event.snapshotGrossDemand();
                accumulator.snapshotUsableSupply = event.snapshotUsableSupply();
                accumulator.snapshotSafetyStock = event.snapshotSafetyStock();
                accumulator.snapshotShortage = event.snapshotShortage();
                accumulator.snapshotExpiryRiskSurplus =
                        event.snapshotExpiryRiskSurplus();
                accumulator.snapshotEventTime = event.eventTimeMillis();
            }
            return accumulator;
        }
        if (event.analyticsType() == AnalyticsType.RECOMMENDATION_DECIDED) {
            accumulator.recommendationDecisions++;
            return accumulator;
        }
        if (event.analyticsType() == AnalyticsType.RECOMMENDATION_OUTCOME_RECORDED) {
            accumulator.recommendationOutcomes++;
            if (event.stockoutOutcome()) {
                accumulator.stockouts++;
            }
            return accumulator;
        }
        BigDecimal quantity = event.canonicalQuantity();
        if (quantity == null) {
            return accumulator;
        }
        switch (event.analyticsType()) {
            case ORDER_INGREDIENT_DEMAND -> accumulator.demand =
                    accumulator.demand.add(quantity);
            case INVENTORY_PURCHASED -> accumulator.receipts =
                    accumulator.receipts.add(quantity);
            case INVENTORY_CONSUMED -> accumulator.consumption =
                    accumulator.consumption.add(quantity);
            case WASTE_RECORDED -> accumulator.waste =
                    accumulator.waste.add(quantity);
            case INVENTORY_EXPIRED -> accumulator.expired =
                    accumulator.expired.add(quantity);
            case INVENTORY_REVERSAL -> accumulator.reversals =
                    accumulator.reversals.add(quantity);
            case INVENTORY_CORRECTED -> accumulator.corrections =
                    accumulator.corrections.add(quantity);
            default -> {
            }
        }
        return accumulator;
    }

    @Override
    public Accumulator mergeAccumulators(Iterable<Accumulator> values) {
        Accumulator result = createAccumulator();
        for (Accumulator value : values) {
            result.demand = result.demand.add(value.demand);
            result.receipts = result.receipts.add(value.receipts);
            result.consumption = result.consumption.add(value.consumption);
            result.waste = result.waste.add(value.waste);
            result.expired = result.expired.add(value.expired);
            result.reversals = result.reversals.add(value.reversals);
            result.corrections = result.corrections.add(value.corrections);
            result.stockouts += value.stockouts;
            result.recommendationDecisions += value.recommendationDecisions;
            result.recommendationOutcomes += value.recommendationOutcomes;
            result.dataQualityEvents += value.dataQualityEvents;
            result.eventCount += value.eventCount;
            if (value.snapshotEventTime >= result.snapshotEventTime) {
                result.snapshotGrossDemand = value.snapshotGrossDemand;
                result.snapshotUsableSupply = value.snapshotUsableSupply;
                result.snapshotSafetyStock = value.snapshotSafetyStock;
                result.snapshotShortage = value.snapshotShortage;
                result.snapshotExpiryRiskSurplus = value.snapshotExpiryRiskSurplus;
                result.snapshotEventTime = value.snapshotEventTime;
            }
            if (result.canonicalUnit == null) {
                result.canonicalUnit = value.canonicalUnit;
            } else if (value.canonicalUnit != null
                    && !result.canonicalUnit.equals(value.canonicalUnit)) {
                result.dataQualityEvents++;
            }
        }
        return result;
    }

    @Override
    public FeatureMetrics extractOutput(Accumulator value) {
        return new FeatureMetrics(
                value.demand, value.receipts, value.consumption, value.waste,
                value.expired, value.reversals, value.corrections,
                value.snapshotGrossDemand, value.snapshotUsableSupply,
                value.snapshotSafetyStock, value.snapshotShortage,
                value.snapshotExpiryRiskSurplus, value.snapshotEventTime,
                value.stockouts, value.recommendationDecisions,
                value.recommendationOutcomes,
                value.dataQualityEvents, value.eventCount, value.canonicalUnit);
    }

    static final class Accumulator implements Serializable {
        private BigDecimal demand = BigDecimal.ZERO;
        private BigDecimal receipts = BigDecimal.ZERO;
        private BigDecimal consumption = BigDecimal.ZERO;
        private BigDecimal waste = BigDecimal.ZERO;
        private BigDecimal expired = BigDecimal.ZERO;
        private BigDecimal reversals = BigDecimal.ZERO;
        private BigDecimal corrections = BigDecimal.ZERO;
        private BigDecimal snapshotGrossDemand;
        private BigDecimal snapshotUsableSupply;
        private BigDecimal snapshotSafetyStock;
        private BigDecimal snapshotShortage;
        private BigDecimal snapshotExpiryRiskSurplus;
        private long snapshotEventTime = Long.MIN_VALUE;
        private long stockouts;
        private long recommendationDecisions;
        private long recommendationOutcomes;
        private long dataQualityEvents;
        private long eventCount;
        private String canonicalUnit;

        @Override
        public boolean equals(Object candidate) {
            if (this == candidate) return true;
            if (!(candidate instanceof Accumulator other)) return false;
            return snapshotEventTime == other.snapshotEventTime
                    && stockouts == other.stockouts
                    && recommendationDecisions == other.recommendationDecisions
                    && recommendationOutcomes == other.recommendationOutcomes
                    && dataQualityEvents == other.dataQualityEvents
                    && eventCount == other.eventCount
                    && Objects.equals(demand, other.demand)
                    && Objects.equals(receipts, other.receipts)
                    && Objects.equals(consumption, other.consumption)
                    && Objects.equals(waste, other.waste)
                    && Objects.equals(expired, other.expired)
                    && Objects.equals(reversals, other.reversals)
                    && Objects.equals(corrections, other.corrections)
                    && Objects.equals(snapshotGrossDemand, other.snapshotGrossDemand)
                    && Objects.equals(snapshotUsableSupply, other.snapshotUsableSupply)
                    && Objects.equals(snapshotSafetyStock, other.snapshotSafetyStock)
                    && Objects.equals(snapshotShortage, other.snapshotShortage)
                    && Objects.equals(snapshotExpiryRiskSurplus,
                            other.snapshotExpiryRiskSurplus)
                    && Objects.equals(canonicalUnit, other.canonicalUnit);
        }

        @Override
        public int hashCode() {
            return Objects.hash(demand, receipts, consumption, waste, expired,
                    reversals, corrections, snapshotGrossDemand,
                    snapshotUsableSupply, snapshotSafetyStock, snapshotShortage,
                    snapshotExpiryRiskSurplus, snapshotEventTime,
                    stockouts, recommendationDecisions,
                    recommendationOutcomes, dataQualityEvents, eventCount,
                    canonicalUnit);
        }
    }
}
