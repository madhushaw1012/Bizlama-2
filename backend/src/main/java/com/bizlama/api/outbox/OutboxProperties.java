package com.bizlama.api.outbox;

import java.time.Duration;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "bizlama.outbox")
public record OutboxProperties(
        @DefaultValue("false") boolean dispatchEnabled,
        @DefaultValue("local") @NotBlank String publisher,
        @DefaultValue("50") @Min(1) int batchSize,
        @DefaultValue("8") @Min(1) int maxAttempts,
        @DefaultValue("PT5S") @NotNull Duration baseBackoff,
        @DefaultValue("PT15M") @NotNull Duration maxBackoff,
        @DefaultValue("PT1M") @NotNull Duration leaseDuration,
        @DefaultValue("PT5S") @NotNull Duration pollInterval,
        @DefaultValue("") String workerId
) {
}
