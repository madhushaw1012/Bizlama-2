package com.bizlama.api.domain;

import java.math.BigDecimal;
import java.time.Instant;

public record OrderListItem (
    String id,
    BigDecimal total,
    Order.Status status,
    Instant createdAt,
    long itemCount
) {

}
