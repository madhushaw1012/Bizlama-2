package com.bizlama.api.dashboard;

import java.util.List;

public record DashboardResponse(
        List<Metric> metrics,
        List<StockItem> stockItems,
        List<PrepRequirement> prepRequirements,
        List<RestockSuggestion> restockSuggestions,
        List<RecentEvent> recentEvents
) {

    public record Metric(
            String label,
            String value,
            String detail,
            String status
    ) {
    }

    public record StockItem(
            String ingredient,
            String quantity,
            String expiryLabel,
            String status
    ) {
    }

    public record PrepRequirement(
            String dish,
            int quantity,
            String ingredients
    ) {
    }

    public record RestockSuggestion(
            String ingredient,
            String quantity,
            String reason
    ) {
    }

    public record RecentEvent(
            String type,
            String description,
            String occurredAt
    ) {
    }
}