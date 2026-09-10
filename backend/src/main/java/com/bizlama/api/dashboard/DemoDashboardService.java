package com.bizlama.api.dashboard;

import com.bizlama.api.config.WorkspaceProperties;
import com.bizlama.api.domain.Ingredient;
import com.bizlama.api.domain.StockLot;
import com.bizlama.api.recommendations.DemandCalculation;
import com.bizlama.api.recommendations.DemandCalculation.DemandScope;
import com.bizlama.api.recommendations.DemandCalculationService;
import com.bizlama.api.recommendations.GovernedRecommendation;
import com.bizlama.api.recommendations.RecommendationService;
import com.bizlama.api.store.OperationalRepository;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

@Service
public class DemoDashboardService {

    private final OperationalRepository repository;
    private final DemandCalculationService demand;
    private final RecommendationService recommendations;
    private final WorkspaceProperties workspace;

    public DemoDashboardService(
            OperationalRepository repository,
            DemandCalculationService demand,
            RecommendationService recommendations,
            WorkspaceProperties workspace
    ) {
        this.repository = repository;
        this.demand = demand;
        this.recommendations = recommendations;
        this.workspace = workspace;
    }

    public DashboardResponse getDashboard(
            String kitchenId,
            String locationId
    ) {
        Instant now = Instant.now();
        LocalDate today = now.atZone(workspace.zoneId()).toLocalDate();
        DemandCalculation calculation = demand.calculate(new DemandScope(
                kitchenId,
                locationId,
                now.minus(Duration.ofDays(30)),
                now.plus(Duration.ofDays(1)),
                now
        ));

        Map<String, Ingredient> ingredients = repository.ingredients()
                .stream()
                .collect(Collectors.toMap(Ingredient::id, value -> value));
        List<StockLot> lots = repository.stockLots();
        List<DashboardResponse.StockItem> stock = lots.stream()
                .map(lot -> stockItem(
                        lot,
                        ingredients.get(lot.ingredientId()),
                        today
                ))
                .toList();
        List<DashboardResponse.PrepRequirement> prep =
                calculation.preparations().stream()
                        .map(value -> new DashboardResponse.PrepRequirement(
                                value.dishName(),
                                value.quantity().intValueExact(),
                                value.requiredIngredientIds().stream()
                                        .map(id -> ingredientName(id, ingredients))
                                        .collect(Collectors.joining(", "))
                        ))
                        .toList();

        long expiring = lots.stream()
                .filter(lot -> !lot.expiresAt().isAfter(today.plusDays(2)))
                .count();
        int prepCount = prep.stream()
                .mapToInt(DashboardResponse.PrepRequirement::quantity)
                .sum();

        List<DashboardResponse.RestockSuggestion> restock =
                recommendations.recommendations(kitchenId, locationId, null)
                        .stream()
                        .filter(value ->
                                value.type() == GovernedRecommendation.Type.PURCHASE
                                        && (value.status()
                                                == GovernedRecommendation.Status.PENDING
                                            || value.status()
                                                == GovernedRecommendation.Status.APPROVED))
                        .map(value -> new DashboardResponse.RestockSuggestion(
                                ingredientName(value.ingredientId(), ingredients),
                                format(value.proposedQuantity()) + " " + value.unit(),
                                "Demand plus safety stock exceeds usable supply"
                        ))
                        .toList();

        List<DashboardResponse.RecentEvent> recentEvents =
                repository.activities().stream()
                        .map(event -> new DashboardResponse.RecentEvent(
                                event.type(),
                                event.description(),
                                friendlyTime(event.occurredAt(), now)
                        ))
                        .toList();

        return new DashboardResponse(
                List.of(
                        new DashboardResponse.Metric(
                                "Stock items",
                                String.valueOf(lots.size()),
                                "Usable, non-expired lots at this location",
                                "neutral"
                        ),
                        new DashboardResponse.Metric(
                                "Expiring soon",
                                String.valueOf(expiring),
                                "Usable lots expiring in the next two days",
                                expiring > 0 ? "warning" : "good"
                        ),
                        new DashboardResponse.Metric(
                                "Today's prep",
                                prepCount + " dishes",
                                "Exact recipe versions from queued orders",
                                "neutral"
                        ),
                        new DashboardResponse.Metric(
                                "Restock signals",
                                String.valueOf(restock.size()),
                                "Current governed purchase recommendations",
                                restock.isEmpty() ? "good" : "warning"
                        )
                ),
                stock,
                prep,
                restock,
                recentEvents
        );
    }

    public void addRecentEvent(String type, String description) {
        repository.addActivity(type, description);
    }

    private DashboardResponse.StockItem stockItem(
            StockLot lot,
            Ingredient ingredient,
            LocalDate today
    ) {
        long days = ChronoUnit.DAYS.between(today, lot.expiresAt());
        String label = days < 0 ? "Expired"
                : days == 0 ? "Expires today"
                : days == 1 ? "Expires tomorrow"
                : "Expires in " + days + " days";
        String status = days <= 0 ? "critical"
                : days <= 2 ? "warning"
                : "good";

        return new DashboardResponse.StockItem(
                ingredient == null ? lot.ingredientId() : ingredient.name(),
                format(lot.quantityRemaining()) + " " + lot.unit(),
                label,
                status
        );
    }

    private String ingredientName(
            String id,
            Map<String, Ingredient> ingredients
    ) {
        Ingredient ingredient = ingredients.get(id);
        return ingredient == null ? id : ingredient.name();
    }

    private String format(BigDecimal value) {
        return value.stripTrailingZeros().toPlainString();
    }

    private String friendlyTime(Instant instant, Instant now) {
        Duration age = Duration.between(instant, now);
        if (age.toMinutes() < 2) {
            return "Just now";
        }
        if (age.toHours() < 1) {
            return age.toMinutes() + " minutes ago";
        }

        ZoneId zone = workspace.zoneId();
        LocalDate date = instant.atZone(zone).toLocalDate();
        if (date.equals(now.atZone(zone).toLocalDate())) {
            return "Today, " + DateTimeFormatter
                    .ofPattern("HH:mm")
                    .withZone(zone)
                    .format(instant);
        }
        return DateTimeFormatter
                .ofPattern("d MMM, HH:mm")
                .withZone(zone)
                .format(instant);
    }
}
