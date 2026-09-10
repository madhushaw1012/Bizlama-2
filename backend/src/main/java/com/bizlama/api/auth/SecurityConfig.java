package com.bizlama.api.auth;

import java.nio.charset.StandardCharsets;

import javax.crypto.spec.SecretKeySpec;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;

import com.nimbusds.jose.jwk.source.ImmutableSecret;

@Configuration
@EnableConfigurationProperties(AuthProperties.class)
public class SecurityConfig {

    private static final String[] READ_ROLES = {
            "OWNER",
            "ADMIN",
            "KITCHEN_OPERATOR",
            "VIEWER"
    };

    private static final String[] OPERATIONAL_WRITE_ROLES = {
            "OWNER",
            "ADMIN",
            "KITCHEN_OPERATOR"
    };

    private static final String[] HIGH_RISK_WRITE_ROLES = {
            "OWNER",
            "ADMIN"
    };

    @Bean
    SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            JwtAuthenticationConverter jwtAuthenticationConverter,
            WorkspaceAccessPolicy workspaceAccessPolicy
    ) throws Exception {
        return http
                .csrf(csrf -> csrf.disable())
                .cors(Customizer.withDefaults())
                .sessionManagement(session ->
                        session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(
                                "/actuator/health",
                                "/actuator/health/**",
                                "/error"
                        ).permitAll()
                        .requestMatchers("/actuator/**").denyAll()
                        .requestMatchers(
                                HttpMethod.GET,
                                "/api/auth/config"
                        ).permitAll()
                        .requestMatchers(
                                HttpMethod.POST,
                                "/api/auth/login"
                        ).permitAll()
                        .requestMatchers(
                                HttpMethod.GET,
                                "/api/auth/me"
                        ).authenticated()
                        .requestMatchers(
                                HttpMethod.OPTIONS,
                                "/api/**"
                        ).permitAll()
                        .requestMatchers(
                                HttpMethod.GET,
                                "/api/**"
                        ).hasAnyRole(READ_ROLES)
                        .requestMatchers(
                                HttpMethod.HEAD,
                                "/api/**"
                        ).hasAnyRole(READ_ROLES)
                        .requestMatchers(
                                "/api/recipes/**",
                                "/api/experiments/*/approve",
                                "/api/stock/purchases",
                                "/api/receipts/*/review",
                                "/api/receipts/*/confirm",
                                "/api/events/confirm",
                                "/api/events/*/supersede",
                                "/api/recommendations/*/approve",
                                "/api/recommendations/*/apply",
                                "/api/recommendations/*/reverse"
                        ).hasAnyRole(HIGH_RISK_WRITE_ROLES)
                        .requestMatchers(
                                HttpMethod.POST,
                                "/api/**"
                        ).hasAnyRole(OPERATIONAL_WRITE_ROLES)
                        .requestMatchers(
                                HttpMethod.PUT,
                                "/api/**"
                        ).hasAnyRole(OPERATIONAL_WRITE_ROLES)
                        .requestMatchers(
                                HttpMethod.PATCH,
                                "/api/**"
                        ).hasAnyRole(OPERATIONAL_WRITE_ROLES)
                        .requestMatchers(
                                HttpMethod.DELETE,
                                "/api/**"
                        ).hasAnyRole(OPERATIONAL_WRITE_ROLES)
                        .requestMatchers("/api/**").denyAll()
                        .anyRequest().permitAll()
                )
                .oauth2ResourceServer(oauth ->
                        oauth.jwt(jwt -> jwt.jwtAuthenticationConverter(
                                jwtAuthenticationConverter
                        )))
                .addFilterBefore(
                        new WorkspaceMembershipFilter(workspaceAccessPolicy),
                        AuthorizationFilter.class
                )
                .build();
    }

    @Bean
    JwtAuthenticationConverter jwtAuthenticationConverter(
            WorkspaceAccessPolicy workspaceAccessPolicy
    ) {
        JwtAuthenticationConverter converter =
                new JwtAuthenticationConverter();

        converter.setJwtGrantedAuthoritiesConverter(
                new JwtRoleAuthoritiesConverter(workspaceAccessPolicy)
        );

        return converter;
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    @ConditionalOnProperty(
            name = "bizlama.auth.mode",
            havingValue = "local",
            matchIfMissing = true
    )
    JwtEncoder localJwtEncoder(AuthProperties properties) {
        return new NimbusJwtEncoder(
                new ImmutableSecret<>(secretKey(properties.tokenSecret()))
        );
    }

    @Bean
    @ConditionalOnProperty(
            name = "bizlama.auth.mode",
            havingValue = "local",
            matchIfMissing = true
    )
    JwtDecoder localJwtDecoder(AuthProperties properties) {
        return NimbusJwtDecoder
                .withSecretKey(secretKey(properties.tokenSecret()))
                .macAlgorithm(MacAlgorithm.HS256)
                .build();
    }

    @Bean
    @ConditionalOnProperty(
            name = "bizlama.auth.mode",
            havingValue = "identity-platform"
    )
    JwtDecoder identityPlatformJwtDecoder(
            @Value("${bizlama.auth.project-id}") String projectId
    ) {
        String issuer =
                "https://securetoken.google.com/" + projectId;

        NimbusJwtDecoder decoder = NimbusJwtDecoder
                .withJwkSetUri(
                        "https://www.googleapis.com/service_accounts/v1/jwk/securetoken@system.gserviceaccount.com"
                )
                .build();

        OAuth2TokenValidator<Jwt> audience = jwt ->
                jwt.getAudience().contains(projectId)
                        ? OAuth2TokenValidatorResult.success()
                        : OAuth2TokenValidatorResult.failure(
                                new OAuth2Error(
                                        "invalid_token",
                                        "Token audience does not match this BizLaMa project",
                                        null
                                )
                        );

        decoder.setJwtValidator(
                new DelegatingOAuth2TokenValidator<>(
                        JwtValidators.createDefaultWithIssuer(issuer),
                        audience
                )
        );

        return decoder;
    }

    private SecretKeySpec secretKey(String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);

        if (bytes.length < 32) {
            throw new IllegalStateException(
                    "BIZLAMA_AUTH_TOKEN_SECRET must contain at least 32 bytes"
            );
        }

        return new SecretKeySpec(bytes, "HmacSHA256");
    }
}
