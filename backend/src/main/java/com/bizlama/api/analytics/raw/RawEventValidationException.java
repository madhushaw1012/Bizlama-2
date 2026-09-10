package com.bizlama.api.analytics.raw;

public class RawEventValidationException extends RuntimeException {

    public RawEventValidationException(String message) {
        super(message);
    }

    public RawEventValidationException(String message, Throwable cause) {
        super(message, cause);
    }
}
