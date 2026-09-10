package com.bizlama.api.experiment;

import java.math.BigDecimal;
import java.time.Instant;

public record ExperimentResponse(
    String dish,
    String theme,
    int themeCount,
    int feedbackCount,
    String metricName,
    BigDecimal currentValue,
    BigDecimal proposedValue,
    String unit,
    int testDurationDays,
    Instant approvedAt,
    ExperimentStatus status) {
}
