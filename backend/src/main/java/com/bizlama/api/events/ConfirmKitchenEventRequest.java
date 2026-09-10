package com.bizlama.api.events;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ConfirmKitchenEventRequest(
        @NotBlank String proposalId,
        @Min(1) int expectedVersion,
        @NotBlank @Size(max = 200) String idempotencyKey
) {
}
