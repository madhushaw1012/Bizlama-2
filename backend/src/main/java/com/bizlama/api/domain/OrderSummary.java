package com.bizlama.api.domain;

import java.math.BigDecimal;
public record OrderSummary (
    long openOrders,
    long readyOrders,
    long completedToday,
    BigDecimal revenueToday) {
}
