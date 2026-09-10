package com.bizlama.api.config;

import java.util.List;
import java.util.regex.Pattern;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "bizlama.web.cors")
public record CorsProperties(List<String> allowedOriginPatterns) {

    private static final Pattern SAFE_ORIGIN_PATTERN = Pattern.compile(
            "^https?://[A-Za-z0-9*.-]+(?::(?:[0-9]{1,5}|\\*))?$"
    );

    public CorsProperties {
        allowedOriginPatterns = allowedOriginPatterns == null
                ? List.of()
                : allowedOriginPatterns.stream()
                        .map(String::trim)
                        .filter(pattern -> !pattern.isEmpty())
                        .distinct()
                        .toList();

        if (allowedOriginPatterns.isEmpty()) {
            throw new IllegalArgumentException(
                    "At least one CORS origin pattern must be configured"
            );
        }
        if (allowedOriginPatterns.stream().anyMatch(
                CorsProperties::invalidPattern)) {
            throw new IllegalArgumentException(
                    "CORS origin patterns must be bounded HTTP(S) origins"
            );
        }
    }

    private static boolean invalidPattern(String pattern) {
        if (!SAFE_ORIGIN_PATTERN.matcher(pattern).matches()) {
            return true;
        }
        String authority = pattern.substring(
                pattern.indexOf("://") + 3
        );
        int portSeparator = authority.indexOf(':');
        String host = portSeparator < 0
                ? authority
                : authority.substring(0, portSeparator);
        return host.chars().noneMatch(Character::isLetterOrDigit);
    }
}
