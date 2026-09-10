package com.bizlama.api.config;

import java.time.Instant;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import com.bizlama.api.quantity.IncompatibleUnitException;
import com.bizlama.api.stock.InsufficientStockException;

@RestControllerAdvice
public class ApiExceptionHandler {
    @ExceptionHandler(IncompatibleUnitException.class)
    ResponseEntity<Map<String, Object>> incompatibleUnit(
            IncompatibleUnitException error
    ) {
        return ResponseEntity
                .status(HttpStatus.UNPROCESSABLE_ENTITY)
                .body(Map.of(
                        "code", "INCOMPATIBLE_UNIT",
                        "detail", error.getMessage(),
                        "timestamp", Instant.now().toString()
                ));
    }

    @ExceptionHandler(InsufficientStockException.class)
    ResponseEntity<Map<String, Object>> insufficientStock(
            InsufficientStockException error
    ) {
        return ResponseEntity
                .status(HttpStatus.UNPROCESSABLE_ENTITY)
                .body(Map.of(
                        "code", "INSUFFICIENT_STOCK",
                        "ingredientId", error.ingredientId(),
                        "requested", error.requested(),
                        "available", error.available(),
                        "shortfall", error.shortfall(),
                        "unit", error.unit(),
                        "detail", error.getMessage(),
                        "timestamp", Instant.now().toString()
                ));
    }

    @ExceptionHandler(IllegalStateException.class)
    ResponseEntity<Map<String, Object>> illegalState(IllegalStateException error) {
        return ResponseEntity
                .status(HttpStatus.UNPROCESSABLE_ENTITY)
                .body(Map.of(
                        "detail", error.getMessage(),
                        "timestamp", Instant.now().toString()
                ));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<Map<String, Object>> validation(
            MethodArgumentNotValidException error
    ) {
        String detail = error.getBindingResult()
                .getFieldErrors()
                .stream()
                .findFirst()
                .map(item -> item.getField() + ": " + item.getDefaultMessage())
                .orElse("Invalid request");

        return ResponseEntity
                .badRequest()
                .body(Map.of(
                        "detail", detail,
                        "timestamp", Instant.now().toString()
                ));
    }
}