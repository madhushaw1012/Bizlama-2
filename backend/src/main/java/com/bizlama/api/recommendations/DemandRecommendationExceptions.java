package com.bizlama.api.recommendations;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

final class DemandRecommendationExceptions {
    private DemandRecommendationExceptions() {
    }
}

@ResponseStatus(HttpStatus.NOT_FOUND)
class RecommendationNotFoundException extends RuntimeException {
    RecommendationNotFoundException(String message) {
        super(message);
    }
}

