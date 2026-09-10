package com.bizlama.api.recommendations;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(HttpStatus.UNPROCESSABLE_ENTITY)
public class DemandValidationException extends RuntimeException {

    public DemandValidationException(String message) {
        super(message);
    }

    public DemandValidationException(String message, Throwable cause) {
        super(message, cause);
    }
}
