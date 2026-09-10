package com.bizlama.api.events;

import jakarta.validation.constraints.NotBlank;

public record ParseKitchenEventRequest (@NotBlank String statement) {

}
