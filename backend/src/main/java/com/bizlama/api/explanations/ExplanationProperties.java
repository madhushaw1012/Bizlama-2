package com.bizlama.api.explanations;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "bizlama.explanations")
public record ExplanationProperties(
        @DefaultValue("demand-explanation-v1")
        @NotBlank String promptVersion,
        @DefaultValue("12000")
        @Min(1000) @Max(50000) int maxInputChars
) {
}
