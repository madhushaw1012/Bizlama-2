package com.bizlama.api.events;

import java.math.BigDecimal;
import java.time.LocalDate;

public record ParsedKitchenEvent(
    KitchenEventType type,
    String item,
    String itemId,
    BigDecimal quantity,
    String unit,
    double confidence,
    String summary,
    String decisionReason,
    KitchenEventIntent intent,
    LocalDate expiresAt,
    String note
) {

    public ParsedKitchenEvent(
            KitchenEventType type,
            String item,
            String itemId,
            BigDecimal quantity,
            String unit,
            double confidence,
            String summary,
            String decisionReason
    ) {
        this(
                type,
                item,
                itemId,
                quantity,
                unit,
                confidence,
                summary,
                decisionReason,
                intentFor(type),
                null,
                null
        );
    }

    public ParsedKitchenEvent {
        if (intent == null) {
            intent = intentFor(type);
        }
    }

    private static KitchenEventIntent intentFor(KitchenEventType type) {
        if (type == null) {
            return KitchenEventIntent.UNKNOWN;
        }
        return switch (type) {
            case PURCHASE, PRODUCTION, WASTE ->
                    KitchenEventIntent.INVENTORY_UPDATE;
            case ORDER -> KitchenEventIntent.ORDER_CAPTURE;
            case FEEDBACK -> KitchenEventIntent.FEEDBACK_CAPTURE;
        };
    }
}
