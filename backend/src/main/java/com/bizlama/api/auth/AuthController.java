package com.bizlama.api.auth;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthProperties properties;
    private final PasswordEncoder passwordEncoder;
    private final ObjectProvider<JwtEncoder> encoder;
    private final WorkspaceAccessPolicy workspaceAccessPolicy;

    public AuthController(
            AuthProperties properties,
            PasswordEncoder passwordEncoder,
            ObjectProvider<JwtEncoder> encoder,
            WorkspaceAccessPolicy workspaceAccessPolicy
    ) {
        this.properties = properties;
        this.passwordEncoder = passwordEncoder;
        this.encoder = encoder;
        this.workspaceAccessPolicy = workspaceAccessPolicy;
    }

    @GetMapping("/config")
    public AuthConfig config() {
        return new AuthConfig(
                properties.identityPlatform() ? "identity-platform" : "local",
                properties.projectId(),
                properties.identityPlatform() ? properties.identityApiKey() : null
        );
    }

    @PostMapping("/login")
    public AuthSession login(@Valid @RequestBody LoginRequest request) {

        if (properties.identityPlatform()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }

        boolean validEmail =
                properties.ownerEmail().equalsIgnoreCase(request.email().trim());

        boolean validPassword =
                passwordEncoder.matches(
                        request.password(),
                        passwordEncoder.encode(properties.ownerPassword())
                );

        if (!validEmail || !validPassword) {
            throw new ResponseStatusException(
                    HttpStatus.UNAUTHORIZED,
                    "Incorrect email or password."
            );
        }

        Instant now = Instant.now();

        Instant expiresAt =
                now.plus(properties.tokenHours(), ChronoUnit.HOURS);

        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer("bizlama-local")
                .issuedAt(now)
                .expiresAt(expiresAt)
                .subject(properties.ownerEmail())
                .claim("name", properties.ownerName())
                .claim("role", "OWNER")
                .build();

        String token = encoder.getObject()
                .encode(
                        JwtEncoderParameters.from(
                                JwsHeader.with(MacAlgorithm.HS256).build(),
                                claims
                        )
                )
                .getTokenValue();

        return new AuthSession(
                token,
                expiresAt,
                new User(
                        properties.ownerEmail(),
                        properties.ownerName(),
                        "OWNER"
                )
        );
    }

    @GetMapping("/me")
    public User me(@AuthenticationPrincipal Jwt jwt) {
        return workspaceAccessPolicy.configuredIdentity(jwt)
                .map(identity -> new User(
                        identity.email(),
                        identity.displayName(),
                        identity.role()
                ))
                .orElseGet(() -> {
                    String email = firstNonBlank(
                            jwt.getClaimAsString("email"),
                            jwt.getSubject(),
                            "Authenticated user"
                    );
                    String name = firstNonBlank(
                            jwt.getClaimAsString("name"),
                            email
                    );
                    return new User(email, name, "UNASSIGNED");
                });
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return "Authenticated user";
    }

    public record AuthConfig(
            String mode,
            String projectId,
            String identityApiKey
    ) {}

    public record LoginRequest(
            @Email @NotBlank String email,
            @NotBlank String password
    ) {}

    public record AuthSession(
            String accessToken,
            Instant expiresAt,
            User user
    ) {}

    public record User(
            String email,
            String name,
            String role
    ) {}
}
