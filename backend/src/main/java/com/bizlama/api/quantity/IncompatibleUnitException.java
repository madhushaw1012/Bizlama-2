package com.bizlama.api.quantity;

public class IncompatibleUnitException extends IllegalArgumentException {

    public IncompatibleUnitException(String message) {
        super(message);
    }
}
