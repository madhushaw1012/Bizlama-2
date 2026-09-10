export interface DashboardResponse {
    metrics: DashboardMetric[];
    stockItems: StockItem[];
    prepRequirements: PrepRequirement[];
    restockSuggestions: RestockSuggestion[];
    recentEvents: RecentEvent[];
}

export interface DashboardMetric {
    label: string;
    value: string;
    detail: string;
    status: 'neutral' | 'warning' | 'success';
}

export interface StockItem {
    ingredient: string;
    quantity: string;
    expiryLabel: string;
    status: 'good' | 'warning' | 'critical';
}

export interface PrepRequirement {
    dish: string;
    quantity: number;
    ingredients: string;
}

export interface RestockSuggestion {
    ingredient: string;
    quantity: string;
    reason: string;
}

export interface RecentEvent {
    type: string;
    description: string;
    occurredAt: string;
}