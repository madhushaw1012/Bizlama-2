package com.bizlama.api.events;

import com.bizlama.api.domain.Dish;
import com.bizlama.api.quantity.CanonicalQuantity;
import com.bizlama.api.quantity.UnitConversionService;
import com.bizlama.api.store.OperationalRepository;

import java.math.BigDecimal;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class KitchenEventParser {

    private static final Pattern QUANTITY_PATTERN = Pattern.compile(
            "(\\d+(?:\\.\\d+)?)\\s*(kg|g|l|ml|litres?|liters?|pieces?|pcs?)?"
    );

    private final OperationalRepository repository;
    private final UnitConversionService units;

    public KitchenEventParser(
            OperationalRepository repository,
            UnitConversionService units) {
        this.repository = repository;
        this.units = units;
    }

    public ParsedKitchenEvent parse(String statement) {
        return parseMany(statement).getFirst();
    }

    public List<ParsedKitchenEvent> parseMany(String statement) {
        String normalized = normalize(statement);
        KitchenEventType explicitType = eventType(normalized);

        if (explicitType == KitchenEventType.PRODUCTION) {
            return List.of(
                    parseSingle(normalized, explicitType, false)
            );
        }

        List<String> clauses = splitIngredientClauses(normalized);
        List<ParsedKitchenEvent> events = new ArrayList<>();

        for (String clause : clauses) {
            if (hasIngredientAndQuantity(clause)) {
                events.add(
                        parseSingle(
                                clause,
                                explicitType,
                                explicitType == null
                        )
                );
            }
        }

        if (events.isEmpty()) {
            events.add(
                    parseSingle(
                            normalized,
                            explicitType,
                            explicitType == null
                    )
            );
        }

        return events;
    }

    private ParsedKitchenEvent parseSingle(
            String normalized,
            KitchenEventType requestedType,
            boolean inferredPurchase
    ) {
        Quantity quantity = extractQuantity(normalized);

        KitchenEventType type =
                requestedType == null
                        ? KitchenEventType.PURCHASE
                        : requestedType;

        if (type == KitchenEventType.WASTE) {

            var match = ingredientMatch(normalized);

            Quantity converted =
                    quantity.toBaseUnit(
                            match.ingredientId(),
                            repository,
                            units
                    );

            return event(
                    KitchenEventType.WASTE,
                    match.canonicalName(),
                    match.ingredientId(),
                    converted,
                    adjustedConfidence(
                            match.confidence(),
                            inferredPurchase
                    ),
                    "Record " + converted.display()
                            + " of " + match.canonicalName()
                            + " as waste",
                    match,
                    inferredPurchase
            );
        }

        if (type == KitchenEventType.PRODUCTION) {

            Dish dish = repository.dishes()
                    .stream()
                    .filter(value ->
                            normalized.contains(
                                    normalize(value.name())
                            )
                    )
                    .max(
                            Comparator.comparingInt(
                                    value -> value.name().length()
                            )
                    )
                    .orElseThrow(() ->
                            new ResponseStatusException(
                                    HttpStatus.UNPROCESSABLE_ENTITY,
                                    "I could not match that dish "
                                            + "to the active menu."
                            )
                    );

            Quantity dishes =
                    new Quantity(quantity.value(), "each");

            return new ParsedKitchenEvent(
                    KitchenEventType.PRODUCTION,
                    dish.name(),
                    dish.id(),
                    dishes.value(),
                    dishes.unit(),
                    0.99,
                    "Record production of "
                            + dishes.display()
                            + " " + dish.name(),
                    "Exact active-menu match"
            );
        }

        if (type == KitchenEventType.PURCHASE) {

            var match = ingredientMatch(normalized);

            Quantity converted =
                    quantity.toBaseUnit(
                            match.ingredientId(),
                            repository,
                            units
                    );

            return event(
                    KitchenEventType.PURCHASE,
                    match.canonicalName(),
                    match.ingredientId(),
                    converted,
                    adjustedConfidence(
                            match.confidence(),
                            inferredPurchase
                    ),
                    "Add " + converted.display()
                            + " of " + match.canonicalName()
                            + " to stock",
                    match,
                    inferredPurchase
            );
        }

        throw new ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "I could not determine the type of kitchen update."
        );
    }

    private ParsedKitchenEvent event(
            KitchenEventType type,
            String name,
            String id,
            Quantity quantity,
            double confidence,
            String summary,
            OperationalRepository.AliasMatch match,
            boolean inferredPurchase
    ) {
        return new ParsedKitchenEvent(
                type,
                name,
                id,
                quantity.value(),
                quantity.unit(),
                confidence,
                summary,
                (inferredPurchase
                        ? "Inferred a stock addition from "
                        + "the item-and-quantity shorthand. "
                        : "")
                        + "Matched \"" + match.alias()
                        + "\" using the "
                        + match.source()
                        + " product dictionary"
        );
    }

    private KitchenEventType eventType(String statement) {

        if (containsAny(
                statement,
                "wasted",
                "discarded",
                "threw away",
                "spoiled",
                "damage"
        )) {
            return KitchenEventType.WASTE;
        }

        if (containsAny(
                statement,
                "made",
                "prepared",
                "cooked",
                "produced"
        )) {
            return KitchenEventType.PRODUCTION;
        }

        if (containsAny(
                statement,
                "bought",
                "buy",
                "add",
                "added",
                "received",
                "brought",
                "purchased"
        )) {
            return KitchenEventType.PURCHASE;
        }

        return null;
    }

    private List<String> splitIngredientClauses(String statement) {

        String actionFree = statement.replaceFirst(
                "^(?:bought|buy|add|added|received|"
                        + "brought|purchased|wasted|discarded)\\s+",
                ""
        );

        return List.of(
                actionFree.split(
                        "\\s+(?:and|plus)\\s+|\\s*,\\s*"
                )
        );
    }

    private boolean hasIngredientAndQuantity(String clause) {
        return QUANTITY_PATTERN.matcher(clause).find()
                && repository.aliases()
                .stream()
                .anyMatch(value ->
                        clause.contains(value.alias())
                );
    }

    private double adjustedConfidence(
            double matchConfidence,
            boolean inferredPurchase
    ) {
        return inferredPurchase
                ? Math.min(matchConfidence, 0.84)
                : matchConfidence;
    }

    private OperationalRepository.AliasMatch ingredientMatch(
            String statement
    ) {
        return repository.aliases()
                .stream()
                .filter(value ->
                        statement.contains(value.alias())
                )
                .max(
                        Comparator.comparingInt(
                                value -> value.alias().length()
                        )
                )
                .orElseThrow(() ->
                        new ResponseStatusException(
                                HttpStatus.UNPROCESSABLE_ENTITY,
                                "I could not match that product to "
                                        + "a known ingredient. Add it to "
                                        + "the catalog or correct the wording."
                        )
                );
    }

    private Quantity extractQuantity(String statement) {

        Matcher matcher = QUANTITY_PATTERN.matcher(statement);

        if (!matcher.find()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Include a quantity, such as 2kg, 500ml, "
                            + "or 10 pieces."
            );
        }

        String unit = matcher.group(2) == null
                ? "pieces"
                : matcher.group(2);

        if (unit.startsWith("lit")) {
            unit = "l";
        }

        if (unit.startsWith("pc")
                || unit.startsWith("piece")) {
            unit = "pieces";
        }

        return new Quantity(
                new BigDecimal(matcher.group(1)),
                unit
        );
    }

    private boolean containsAny(
            String statement,
            String... terms
    ) {
        for (String term : terms) {
            if (statement.contains(term)) {
                return true;
            }
        }

        return false;
    }

    private String normalize(String value) {
        return Normalizer
                .normalize(value, Normalizer.Form.NFKD)
                .replaceAll("\\p{M}", "")
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", " ")
                .trim();
    }

    private record Quantity(
            BigDecimal value,
            String unit
    ) {

        Quantity toBaseUnit(
                String ingredientId,
                OperationalRepository repository,
                UnitConversionService units
        ) {
            var ingredient = repository
                    .ingredient(ingredientId)
                    .orElseThrow(() -> new IllegalStateException(
                            "Ingredient disappeared during quantity normalisation"
                    ));
            CanonicalQuantity converted = units.toIngredientBase(
                    ingredient,
                    value,
                    unit
            );

            return new Quantity(
                    converted.quantity(),
                    converted.unit()
            );
        }

        String display() {
            return value.stripTrailingZeros().toPlainString() + " " + unit;
        }
    }
}