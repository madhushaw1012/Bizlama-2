package com.bizlama.api.explanations;

import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

@Component
public class ExplanationRedactor {

    private static final Pattern EMAIL = Pattern.compile(
            "(?i)[\\p{Alnum}._%+-]+@[\\p{Alnum}.-]+\\.[A-Z]{2,}"
    );
    private static final Pattern PHONE = Pattern.compile(
            "(?<!\\d)(?:\\+?\\d[\\d .()-]{6,}\\d)(?!\\d)"
    );
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    public String input(String value, int maxLength) {
        String redacted = redact(value);
        return redacted.length() <= maxLength
                ? redacted
                : redacted.substring(0, maxLength);
    }

    public String output(String value, int maxLength, String field) {
        if (value == null) {
            throw new IllegalArgumentException(field + " is required");
        }
        String normalized = WHITESPACE.matcher(value.trim()).replaceAll(" ");
        if (normalized.isBlank() || normalized.length() > maxLength) {
            throw new IllegalArgumentException(
                    field + " must contain from 1 to " + maxLength + " characters"
            );
        }
        return redact(normalized);
    }

    public String error(Throwable error) {
        String message = error == null ? "" : String.valueOf(error.getMessage());
        if (message.isBlank() && error != null) {
            message = error.getClass().getSimpleName();
        }
        return input(message, 500);
    }

    private String redact(String value) {
        if (value == null) {
            return "";
        }
        String normalized = WHITESPACE.matcher(value.trim()).replaceAll(" ");
        normalized = EMAIL.matcher(normalized).replaceAll("[redacted-email]");
        return PHONE.matcher(normalized).replaceAll("[redacted-phone]");
    }
}
