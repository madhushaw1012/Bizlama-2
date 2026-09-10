package com.bizlama.api.orders;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** Raised when an order lifecycle change violates the server-side state machine. */
@ResponseStatus(HttpStatus.CONFLICT)
public final class OrderTransitionConflictException extends RuntimeException {

    public OrderTransitionConflictException(String message) {
        super(message);
    }
}
