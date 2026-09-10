package com.bizlama.api.explanations;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class DeterministicExplanationProvider implements ExplanationProvider {

    private final ExplanationProperties properties;
    private final ExplanationSupport support;

    public DeterministicExplanationProvider(
            ExplanationProperties properties,
            ExplanationSupport support
    ) {
        this.properties = properties;
        this.support = support;
    }

    @Override
    public Metadata metadata() {
        return new Metadata(
                "deterministic",
                "template-v1",
                properties.promptVersion()
        );
    }

    @Override
    public RecommendationExplanation.Content explain(Request request) {
        Snapshot snapshot = request.snapshot();
        String quantity = snapshot.authoritativeQuantity().toPlainString()
                + " " + snapshot.authoritativeUnit();
        String summary = switch (snapshot.recommendationType()) {
            case "PURCHASE" ->
                    "Purchase " + quantity + " of " + snapshot.subjectName()
                            + " to cover the deterministic supply gap.";
            case "PREPARE" ->
                    "Prepare " + quantity + " of " + snapshot.subjectName()
                            + " for accepted demand in the selected horizon.";
            case "UTILISE_EXPIRING_STOCK" ->
                    "Use " + quantity + " of " + snapshot.subjectName()
                            + " while it remains eligible before expiry.";
            case "REDUCE_OR_AVOID_PURCHASE" ->
                    "Reduce or avoid purchasing " + quantity + " of "
                            + snapshot.subjectName()
                            + " because usable supply already covers demand.";
            default -> "Review the deterministic recommendation for "
                    + snapshot.subjectName() + ".";
        };

        Map<String, Object> evidence = snapshot.evidence();
        List<String> drivers = new ArrayList<>();
        addMetric(drivers, evidence, "grossDemand", "Gross demand");
        addMetric(drivers, evidence, "usableSupply", "Usable supply");
        addMetric(drivers, evidence, "shortage", "Calculated shortage");
        addMetric(
                drivers,
                evidence,
                "expiryRiskSurplus",
                "Expiry-risk surplus");
        addMetric(
                drivers,
                evidence,
                "preparationQuantity",
                "Required preparation");
        if (drivers.isEmpty()) {
            drivers.add("Reason code: " + snapshot.reasonCode() + ".");
        }
        drivers.add(
                "Confidence is " + snapshot.confidence().toPlainString()
                        + " from the deterministic evidence snapshot.");
        if (drivers.size() > 6) {
            drivers = new ArrayList<>(drivers.subList(0, 6));
        }

        List<String> caveats = List.of(
                "The authoritative quantity is calculated by "
                        + snapshot.calculationMethod()
                        + "; this explanation cannot change it.",
                "Recalculate after material order, recipe, inventory, or safety-stock changes."
        );
        return support.validate(new RecommendationExplanation.Content(
                summary,
                drivers,
                caveats
        ));
    }

    private void addMetric(
            List<String> result,
            Map<String, Object> evidence,
            String key,
            String label
    ) {
        Object value = evidence.get(key);
        if (value instanceof BigDecimal decimal) {
            result.add(label + ": " + decimal.toPlainString() + ".");
        }
    }
}
