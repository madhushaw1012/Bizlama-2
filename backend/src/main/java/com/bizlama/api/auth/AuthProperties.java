package com.bizlama.api.auth;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "bizlama.auth")
public record AuthProperties(
        String mode,
        String ownerEmail,
        String ownerName,
        String ownerPassword,
        String tokenSecret,
        long tokenHours,
        String projectId,
        String identityApiKey
) {
    public boolean identityPlatform() {
        return "identity-platform".equalsIgnoreCase(mode);
    }
}