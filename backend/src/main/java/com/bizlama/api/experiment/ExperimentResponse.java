package com.bizlama.api.experiment;

import java.math.BigDecimal;

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
    ExperimentStatus status) {
}