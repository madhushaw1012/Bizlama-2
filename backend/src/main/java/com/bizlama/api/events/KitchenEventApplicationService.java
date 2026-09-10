package com.bizlama.api.events;

import com.bizlama.api.config.WorkspaceProperties;
import com.bizlama.api.domain.Dish;
import com.bizlama.api.domain.RecipeVersion;
import com.bizlama.api.domain.StockMovement;
import com.bizlama.api.feedback.FeedbackService;
import com.bizlama.api.orders.OrderApplicationService;
import com.bizlama.api.shelflife.ShelfLifeGuidanceProvider;
import com.bizlama.api.stock.ExpiryProvenance;
import com.bizlama.api.stock.InventoryAllocationService;
import com.bizlama.api.stock.InventoryPurchaseService;
import com.bizlama.api.store.OperationalRepository;
import java.math.BigDecimal;
import java.math.MathContext;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class KitchenEventApplicationService {

    private static final MathContext CALCULATION_CONTEXT = MathContext.DECIMAL128;

    private final OperationalRepository repository;
    private final ShelfLifeGuidanceProvider shelfLife;
    private final InventoryPurchaseService purchases;
    private final InventoryAllocationService allocations;
    private final JdbcClient jdbc;
    private final OrderApplicationService orders;
    private final FeedbackService feedback;
    private final WorkspaceProperties workspace;

    public KitchenEventApplicationService(
            OperationalRepository repository,
            ShelfLifeGuidanceProvider shelfLife,
            InventoryPurchaseService purchases,
            InventoryAllocationService allocations,
            OrderApplicationService orders,
            FeedbackService feedback,
            JdbcClient jdbc,
            WorkspaceProperties workspace
    ) {
        this.repository = repository;
        this.shelfLife = shelfLife;
        this.purchases = purchases;
        this.allocations = allocations;
        this.orders = orders;
        this.feedback = feedback;
        this.jdbc = jdbc;
        this.workspace = workspace;
    }

    @Transactional
    public void apply(
            ParsedKitchenEvent event,
            String proposalId,
            Instant actionAt
    ) {
        validate(event);

        switch (event.type()) {
            case PURCHASE -> {
                LocalDate purchased = kitchenDate(actionAt);
                LocalDate expires;
                ExpiryProvenance provenance;
                if (event.expiresAt() != null) {
                    expires = event.expiresAt();
                    provenance = ExpiryProvenance.OWNER_CONFIRMED;
                } else {
                    var guidance = shelfLife
                            .findForIngredient(event.itemId())
                            .orElseThrow(() -> new IllegalStateException(
                                    "Purchase expiry requires a reviewed shelf-life rule."
                            ));
                    expires = guidance.expiresOn(purchased);
                    provenance = ExpiryProvenance.REVIEWED_SHELF_LIFE_RULE;
                }
                purchases.add(new InventoryPurchaseService.Purchase(
                        null,
                        workspace.kitchenId(),
                        workspace.locationId(),
                        event.itemId(),
                        event.quantity(),
                        event.unit(),
                        event.quantity(),
                        event.unit(),
                        purchased,
                        expires,
                        provenance,
                        "kitchen-event:" + proposalId,
                        "kitchen-event",
                        proposalId,
                        null,
                        null,
                        actionAt
                ));
            }
            case WASTE -> allocations.allocate(
                    workspace.kitchenId(),
                    workspace.locationId(),
                    event.itemId(),
                    event.quantity(),
                    event.unit(),
                    actionAt,
                    StockMovement.MovementType.WASTE,
                    "waste-event",
                    proposalId
            );
            case PRODUCTION -> applyProduction(
                    event,
                    proposalId,
                    actionAt
            );
            case ORDER -> orders.create(
                    List.of(new OrderApplicationService.Line(
                            event.itemId(),
                            event.quantity().intValueExact()
                    )),
                    actionAt
            );
            case FEEDBACK -> applyFeedback(event);
        }

        if (event.intent() == KitchenEventIntent.INVENTORY_UPDATE) {
            repository.addActivity(label(event.type()), event.summary());
        }
    }

    @Transactional
    public void applyAll(
            List<ParsedKitchenEvent> events,
            String proposalId,
            Instant actionAt
    ) {
        if (events == null || events.isEmpty()) {
            throw new IllegalArgumentException(
                    "At least one kitchen event is required."
            );
        }
        boolean containsOrder = events.stream()
                .anyMatch(event -> event.type() == KitchenEventType.ORDER);
        if (containsOrder) {
            if (events.stream()
                    .anyMatch(event -> event.type() != KitchenEventType.ORDER)) {
                throw new IllegalArgumentException(
                        "An order proposal cannot mix other activity intents."
                );
            }
            events.forEach(this::validate);
            orders.create(events.stream()
                    .map(event -> new OrderApplicationService.Line(
                            event.itemId(),
                            event.quantity().intValueExact()
                    ))
                    .toList(), actionAt);
            return;
        }
        for (ParsedKitchenEvent event : events) {
            apply(event, proposalId, actionAt);
        }
    }

    private void applyFeedback(ParsedKitchenEvent event) {
        Dish dish = repository.dish(event.itemId())
                .filter(Dish::active)
                .orElseThrow(() -> new IllegalStateException(
                        "Feedback dish is no longer active."
                ));
        feedback.capture(
                dish.activeRecipeVersionId(),
                event.note(),
                event.quantity().intValueExact(),
                "kitchen-event"
        );
    }

    private void applyProduction(
            ParsedKitchenEvent event,
            String proposalId,
            Instant actionAt
    ) {
        RecipeVersion recipe = repository
                .dish(event.itemId())
                .flatMap(dish ->
                        repository.recipe(dish.activeRecipeVersionId())
                )
                .orElseThrow(() -> new IllegalStateException(
                        "No active recipe for " + event.item()
                ));

        BigDecimal yield = jdbc.sql("""
                        SELECT yield_quantity
                        FROM recipe_versions
                        WHERE id = :recipe
                          AND kitchen_id = :kitchen
                          AND active = TRUE
                        """)
                .param("recipe", recipe.id())
                .param("kitchen", workspace.kitchenId())
                .query(BigDecimal.class)
                .optional()
                .orElseThrow(() -> new IllegalStateException(
                        "Active recipe yield is unavailable."
                ));
        if (yield.signum() <= 0) {
            throw new IllegalStateException("Active recipe yield must be positive.");
        }

        for (RecipeVersion.RecipeIngredient ingredient : recipe.ingredients()) {
            BigDecimal required = ingredient.quantity()
                    .multiply(event.quantity(), CALCULATION_CONTEXT)
                    .divide(yield, CALCULATION_CONTEXT);
            allocations.allocate(
                    workspace.kitchenId(),
                    workspace.locationId(),
                    ingredient.ingredientId(),
                    required,
                    ingredient.unit(),
                    actionAt,
                    StockMovement.MovementType.PRODUCTION_CONSUMPTION,
                    "production-run",
                    proposalId
            );
        }
    }

    private LocalDate kitchenDate(Instant actionAt) {
        String timezone = jdbc.sql("""
                        SELECT timezone
                        FROM kitchens
                        WHERE id = :kitchen
                          AND active = TRUE
                        """)
                .param("kitchen", workspace.kitchenId())
                .query(String.class)
                .optional()
                .orElseThrow(() -> new IllegalStateException(
                        "Active kitchen timezone is unavailable."
                ));
        return actionAt.atZone(ZoneId.of(timezone)).toLocalDate();
    }

    private void validate(ParsedKitchenEvent event) {
        if (event == null || event.type() == null) {
            throw new IllegalArgumentException("Kitchen event type is required.");
        }
        if (event.itemId() == null || event.itemId().isBlank()) {
            throw new IllegalArgumentException("Kitchen event item is required.");
        }
        if (event.quantity() == null || event.quantity().signum() <= 0) {
            throw new IllegalArgumentException(
                    "Kitchen event quantity must be positive."
            );
        }
        if (event.unit() == null || event.unit().isBlank()) {
            throw new IllegalArgumentException("Kitchen event unit is required.");
        }
        if ((event.type() == KitchenEventType.ORDER
                || event.type() == KitchenEventType.FEEDBACK)
                && event.quantity().stripTrailingZeros().scale() > 0) {
            throw new IllegalArgumentException(
                    "Order quantities and ratings must be whole numbers."
            );
        }
        if (event.type() == KitchenEventType.FEEDBACK
                && (event.quantity().intValueExact() < 1
                || event.quantity().intValueExact() > 5)) {
            throw new IllegalArgumentException(
                    "Feedback rating must be between 1 and 5."
            );
        }
        if (event.type() == KitchenEventType.FEEDBACK
                && (event.note() == null || event.note().isBlank())) {
            throw new IllegalArgumentException("Feedback text is required.");
        }
    }

    private String label(KitchenEventType type) {
        return switch (type) {
            case PURCHASE -> "Purchase";
            case PRODUCTION -> "Production";
            case WASTE -> "Waste";
            case ORDER -> "Order";
            case FEEDBACK -> "Feedback";
        };
    }
}