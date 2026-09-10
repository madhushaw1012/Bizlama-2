package com.bizlama.api.domain;

public record InventorySummary (
    long ingredients,
    long activeLots,
    long expiringLots,
    long expiredLots) {

}
