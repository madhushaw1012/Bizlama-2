package com.bizlama.api.events;

import com.bizlama.api.config.WorkspaceProperties;
import com.bizlama.api.domain.Dish;
import com.bizlama.api.domain.RecipeVersion;
import com.bizlama.api.store.OperationalRepository;
import java.math.BigDecimal;
import java.text.Normalizer;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * Deterministic first-stage router for common Record Activity commands.
 * User content is treated only as data and every returned event still requires
 * confirmation before an application service can write it.
 */
@Service
public class KitchenEventIntentRouter {

    private static final double MIN_CONFIDENCE = 0.80;
    private static final Pattern ORDER_LINE = Pattern.compile(
            "^(\\d+)\\s+(.+)$"
    );
    private static final Pattern RATING = Pattern.compile(
            "\\brating\\s+([1-5])\\b"
    );
    private static final Pattern EXPIRY = Pattern.compile(
            "\\bexpires?(?:\\s+on)?\\s+(\\d{4}-\\d{2}-\\d{2})\\b",
            Pattern.CASE_INSENSITIVE
    );

    private final KitchenEventParser inventoryParser;
    private final OperationalRepository repository;
    private final WorkspaceProperties workspace;

    public KitchenEventIntentRouter(
            KitchenEventParser inventoryParser,
            OperationalRepository repository,
            WorkspaceProperties workspace
    ) {
        this.inventoryParser = inventoryParser;
        this.repository = repository;
        this.workspace = workspace;
    }

    public List<ParsedKitchenEvent> route(String statement) {
        if (statement == null || statement.isBlank()) {
            throw clarify("Describe the kitchen activity to review.");
        }
        String normalized = normalize(statement);
        if (normalized.startsWith("order ")
                || normalized.startsWith("capture order ")) {
            return parseOrder(normalized);
        }
        if (looksLikeFeedback(normalized)) {
            return List.of(parseFeedback(statement, normalized));
        }
        return parseInventory(statement);
    }

    private List<ParsedKitchenEvent> parseInventory(String statement) {
        LocalDate expiry = expiry(statement);
        List<ParsedKitchenEvent> parsed = inventoryParser.parseMany(statement);
        List<ParsedKitchenEvent> result = parsed.stream()
                .map(event -> {
                    if (expiry != null
                            && event.type() != KitchenEventType.PURCHASE) {
                        throw clarify(
                                "An explicit expiry date is valid only for a purchase."
                        );
                    }
                    return new ParsedKitchenEvent(
                            event.type(),
                            event.item(),
                            event.itemId(),
                            event.quantity(),
                            event.unit(),
                            event.confidence(),
                            event.summary()
                                    + (expiry == null
                                    ? ""
                                    : ", expiring " + expiry),
                            event.decisionReason(),
                            KitchenEventIntent.INVENTORY_UPDATE,
                            expiry,
                            null
                    );
                })
                .toList();
        requireConfident(result);
        return result;
    }

    private List<ParsedKitchenEvent> parseOrder(String normalized) {
        String lines = normalized.replaceFirst(
                "^(?:capture\\s+)?order\\s+",
                ""
        );
        List<String> clauses = List.of(lines.split("\\s+(?:and|plus)\\s+"));
        List<ParsedKitchenEvent> result = new ArrayList<>();
        for (String clause : clauses) {
            Matcher quantity = ORDER_LINE.matcher(clause.trim());
            if (!quantity.matches()) {
                throw clarify(
                        "Include a whole-number quantity before every ordered dish."
                );
            }
            int count;
            try {
                count = Integer.parseInt(quantity.group(1));
            } catch (NumberFormatException error) {
                throw clarify("Order quantities must be whole numbers.");
            }
            if (count <= 0) {
                throw clarify("Order quantities must be positive.");
            }
            Dish dish = dishMatch(quantity.group(2))
                    .orElseThrow(() -> clarify(
                            "I could not match every ordered item to an active dish."
                    ));
            if (dish.activeRecipeVersionId() == null
                    || repository.recipe(dish.activeRecipeVersionId())
                    .filter(RecipeVersion::active)
                    .isEmpty()) {
                throw clarify(
                        "The matched dish has no active recipe and cannot be ordered."
                );
            }
            result.add(new ParsedKitchenEvent(
                    KitchenEventType.ORDER,
                    dish.name(),
                    dish.id(),
                    BigDecimal.valueOf(count),
                    "each",
                    0.99,
                    "Add " + count + " " + dish.name() + " to a new order",
                    "Exact tenant-scoped active-menu match",
                    KitchenEventIntent.ORDER_CAPTURE,
                    null,
                    null
            ));
        }
        if (result.isEmpty()) {
            throw clarify("Include at least one dish and quantity.");
        }
        return List.copyOf(result);
    }

    private ParsedKitchenEvent parseFeedback(
            String original,
            String normalized
    ) {
        Dish dish = dishMatch(normalized)
                .orElseThrow(() -> clarify(
                        "I could not match the feedback to an active dish."
                ));
        Matcher ratingMatch = RATING.matcher(normalized);
        if (!ratingMatch.find()) {
            throw clarify(
                    "Include a rating from 1 to 5 with the dish feedback."
            );
        }
        int rating = Integer.parseInt(ratingMatch.group(1));
        String recipeId = dish.activeRecipeVersionId();
        if (recipeId == null
                || repository.recipe(recipeId)
                .filter(RecipeVersion::active)
                .isEmpty()) {
            throw clarify(
                    "The matched dish has no active recipe for feedback."
            );
        }
        return new ParsedKitchenEvent(
                KitchenEventType.FEEDBACK,
                dish.name(),
                dish.id(),
                BigDecimal.valueOf(rating),
                "stars",
                0.99,
                "Capture rating " + rating + " feedback for " + dish.name(),
                "Exact tenant-scoped dish and active-recipe match",
                KitchenEventIntent.FEEDBACK_CAPTURE,
                null,
                original.trim()
        );
    }

    private LocalDate expiry(String statement) {
        Matcher matcher = EXPIRY.matcher(statement);
        if (!matcher.find()) {
            if (normalize(statement).contains("expire")) {
                throw clarify(
                        "Use an explicit expiry date in YYYY-MM-DD format."
                );
            }
            return null;
        }
        LocalDate value;
        try {
            value = LocalDate.parse(matcher.group(1));
        } catch (DateTimeParseException error) {
            throw clarify("The expiry date is not valid.");
        }
        LocalDate today = LocalDate.now(workspace.zoneId());
        if (!value.isAfter(today)) {
            throw clarify("The purchase expiry date must be in the future.");
        }
        return value;
    }

    private java.util.Optional<Dish> dishMatch(String statement) {
        String normalized = normalize(statement);
        return repository.dishes()
                .stream()
                .filter(dish -> {
                    String name = normalize(dish.name());
                    return normalized.contains(name)
                            || normalized.contains(pluralizeLastWord(name));
                })
                .max(Comparator.comparingInt(dish -> dish.name().length()));
    }

    private String pluralizeLastWord(String phrase) {
        int separator = phrase.lastIndexOf(' ');
        String prefix = separator < 0 ? "" : phrase.substring(0, separator + 1);
        String word = separator < 0 ? phrase : phrase.substring(separator + 1);
        String plural;
        if (word.endsWith("ch") || word.endsWith("sh")
                || word.endsWith("s") || word.endsWith("x")
                || word.endsWith("z")) {
            plural = word + "es";
        } else if (word.endsWith("y") && word.length() > 1) {
            plural = word.substring(0, word.length() - 1) + "ies";
        } else {
            plural = word + "s";
        }
        return prefix + plural;
    }

    private boolean looksLikeFeedback(String statement) {
        return statement.contains(" rating ")
                || statement.startsWith("rating ")
                || statement.contains(" was dry")
                || statement.contains(" was salty")
                || statement.contains(" tasted ")
                || statement.contains(" felt ");
    }

    private void requireConfident(List<ParsedKitchenEvent> events) {
        if (events.isEmpty() || events.stream()
                .anyMatch(event -> event.confidence() < MIN_CONFIDENCE)) {
            throw clarify(
                    "The match is uncertain. Use an exact catalog name and quantity."
            );
        }
    }

    private String normalize(String value) {
        return Normalizer.normalize(value, Normalizer.Form.NFKD)
                .replaceAll("\\p{M}", "")
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", " ")
                .trim();
    }

    private ResponseStatusException clarify(String message) {
        return new ResponseStatusException(
                HttpStatus.UNPROCESSABLE_ENTITY,
                message
        );
    }
}
