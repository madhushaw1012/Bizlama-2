package com.bizlama.api.explanations;

import com.bizlama.api.recommendations.DemandCalculation;
import com.bizlama.api.recommendations.DemandCalculation.IngredientDemand;
import com.bizlama.api.recommendations.DemandCalculation.PreparationDemand;
import com.bizlama.api.recommendations.GovernedRecommendation;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
public class ExplanationSupport {

    static final int RESPONSE_SCHEMA_VERSION = 1;
    private static final Set<String> RESPONSE_FIELDS =
            Set.of("summary", "drivers", "caveats");

    private final ObjectMapper objectMapper;
    private final ExplanationRedactor redactor;

    public ExplanationSupport(
            ObjectMapper objectMapper,
            ExplanationRedactor redactor
    ) {
        this.objectMapper = objectMapper;
        this.redactor = redactor;
    }

    public ExplanationProvider.Snapshot snapshot(
            GovernedRecommendation recommendation,
            DemandCalculation calculation
    ) {
        Map<String, Object> evidence = new LinkedHashMap<>();
        String subjectId;
        String subjectName;

        if (recommendation.ingredientId() != null) {
            IngredientDemand ingredient = calculation.ingredients().stream()
                    .filter(value -> value.ingredientId().equals(
                            recommendation.ingredientId()))
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException(
                            "Recommendation ingredient is absent from its calculation snapshot"
                    ));
            subjectId = redactor.input(ingredient.ingredientId(), 160);
            subjectName = redactor.input(ingredient.ingredientName(), 160);
            evidence.put("grossDemand", ingredient.grossDemand());
            evidence.put("onHandUsableSupply", ingredient.onHandUsableSupply());
            evidence.put(
                    "horizonEndSurvivingSupply",
                    ingredient.horizonEndSurvivingSupply());
            evidence.put("usableSupply", ingredient.usableSupply());
            evidence.put("safetyStock", ingredient.safetyStock());
            evidence.put(
                    "safetyStockSource",
                    redactor.input(ingredient.safetyStockSource(), 160));
            evidence.put("shortage", ingredient.shortage());
            evidence.put(
                    "timePhasedShortfall",
                    ingredient.timePhasedShortfall());
            evidence.put("expiryRiskSurplus", ingredient.expiryRiskSurplus());
            evidence.put(
                    "contributingOrderCount",
                    ingredient.contributingOrderIds().size());
            evidence.put(
                    "contributingLotCount",
                    ingredient.contributingLots().size());
            evidence.put("excludedLotCount", ingredient.excludedLots().size());
        } else {
            PreparationDemand preparation = calculation.preparations().stream()
                    .filter(value -> value.dishId().equals(recommendation.dishId())
                            && value.recipeVersionId().equals(
                                    recommendation.recipeVersionId()))
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException(
                            "Recommendation dish is absent from its calculation snapshot"
                    ));
            subjectId = redactor.input(preparation.dishId(), 160);
            subjectName = redactor.input(preparation.dishName(), 160);
            evidence.put("preparationQuantity", preparation.quantity());
            evidence.put("preparationUnit", preparation.unit());
            evidence.put(
                    "contributingOrderCount",
                    preparation.contributingOrderIds().size());
            evidence.put(
                    "requiredIngredientCount",
                    preparation.requiredIngredientIds().size());
        }

        return new ExplanationProvider.Snapshot(
                1,
                redactor.input(recommendation.id(), 160),
                recommendation.version(),
                redactor.input(recommendation.calculationId(), 160),
                recommendation.type().name(),
                recommendation.status().name(),
                recommendation.riskTier().name(),
                redactor.input(recommendation.reasonCode(), 160),
                recommendation.proposedQuantity(),
                redactor.input(recommendation.unit(), 40),
                recommendation.confidence(),
                subjectId,
                subjectName,
                calculation.calculationMethod(),
                calculation.scope().horizonStart(),
                calculation.scope().horizonEnd(),
                calculation.scope().asOf(),
                evidence
        );
    }

    public RecommendationExplanation.Content parse(String json) {
        try {
            JsonNode root = objectMapper.readTree(json);
            if (root == null || !root.isObject()
                    || !fieldNames(root).equals(RESPONSE_FIELDS)) {
                throw new IllegalArgumentException(
                        "Explanation response must contain only summary, drivers, and caveats"
                );
            }
            String summary = text(root.get("summary"), "summary", 800);
            List<String> drivers = strings(
                    root.get("drivers"), "drivers", 1, 6, 300);
            List<String> caveats = strings(
                    root.get("caveats"), "caveats", 0, 5, 300);
            return new RecommendationExplanation.Content(
                    summary, drivers, caveats);
        } catch (IllegalArgumentException error) {
            throw error;
        } catch (Exception error) {
            throw new IllegalArgumentException(
                    "Explanation response is not valid JSON",
                    error
            );
        }
    }

    public RecommendationExplanation.Content validate(
            RecommendationExplanation.Content content
    ) {
        if (content == null) {
            throw new IllegalArgumentException("Explanation content is required");
        }
        String summary = redactor.output(content.summary(), 800, "summary");
        List<String> drivers = validateStrings(
                content.drivers(), "drivers", 1, 6, 300);
        List<String> caveats = validateStrings(
                content.caveats(), "caveats", 0, 5, 300);
        return new RecommendationExplanation.Content(summary, drivers, caveats);
    }

    public RecommendationExplanation.Content validateGenerated(
            RecommendationExplanation.Content content
    ) {
        RecommendationExplanation.Content validated = validate(content);
        requireQualitative(validated.summary(), "summary");
        validated.drivers().forEach(value ->
                requireQualitative(value, "drivers entry"));
        validated.caveats().forEach(value ->
                requireQualitative(value, "caveats entry"));
        return validated;
    }

    private void requireQualitative(String value, String field) {
        if (value.codePoints().anyMatch(Character::isDigit)) {
            throw new IllegalArgumentException(
                    field + " must not restate numeric evidence");
        }
    }

    public String hash(String promptVersion, String canonicalInput) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(
                    (promptVersion + "\n" + canonicalInput)
                            .getBytes(StandardCharsets.UTF_8)
            ));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private String text(JsonNode node, String field, int maxLength) {
        if (node == null || !node.isTextual()) {
            throw new IllegalArgumentException(field + " must be text");
        }
        return redactor.output(node.textValue(), maxLength, field);
    }

    private List<String> strings(
            JsonNode node,
            String field,
            int minimum,
            int maximum,
            int maxLength
    ) {
        if (node == null || !node.isArray()
                || node.size() < minimum || node.size() > maximum) {
            throw new IllegalArgumentException(
                    field + " must contain from " + minimum + " to "
                            + maximum + " entries"
            );
        }
        List<String> result = new ArrayList<>();
        for (JsonNode value : node) {
            if (!value.isTextual()) {
                throw new IllegalArgumentException(
                        field + " entries must be text"
                );
            }
            result.add(redactor.output(
                    value.textValue(), maxLength, field + " entry"));
        }
        return List.copyOf(result);
    }

    private List<String> validateStrings(
            List<String> values,
            String field,
            int minimum,
            int maximum,
            int maxLength
    ) {
        if (values == null || values.size() < minimum
                || values.size() > maximum) {
            throw new IllegalArgumentException(
                    field + " must contain from " + minimum + " to "
                            + maximum + " entries"
            );
        }
        return values.stream()
                .map(value -> redactor.output(
                        value, maxLength, field + " entry"))
                .toList();
    }

    private Set<String> fieldNames(JsonNode node) {
        Iterator<String> names = node.fieldNames();
        java.util.HashSet<String> result = new java.util.HashSet<>();
        names.forEachRemaining(result::add);
        return Set.copyOf(result);
    }
}
