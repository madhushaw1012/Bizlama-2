package com.bizlama.api.events;

import java.math.BigDecimal;

public record ParsedKitchenEvent(
    KitchenEventType type,
    String item,
    String itemId,
    BigDecimal quantity,
    String unit,
    double confidence,
    String summary,
    String decisionReason
) {

}
