package com.bizlama.api.auth;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.bizlama.api.config.WebConfig;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.util.TestPropertyValues;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mock.web.MockServletContext;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

class SecurityConfigAuthorizationTest {

    private static final String[] HIGH_RISK_PATHS = {
            "/api/recipes/dishes",
            "/api/experiments/experiment-1/approve",
            "/api/stock/purchases",
            "/api/events/confirm"
    };

    private AnnotationConfigWebApplicationContext context;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        context = new AnnotationConfigWebApplicationContext();
        context.setServletContext(new MockServletContext());
        TestPropertyValues.of(
                "bizlama.web.cors.allowed-origin-patterns="
                        + "https://bizlama-api-*.asia-south1.run.app"
        ).applyTo(context);
        context.register(TestConfiguration.class, WebConfig.class);
        context.refresh();

        mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(context.getBean(FilterChainProxy.class))
                .build();
    }

    @AfterEach
    void tearDown() {
        context.close();
    }

    @Test
    void authAndHealthEndpointsRemainPublic() throws Exception {
        mockMvc.perform(get("/api/auth/config"))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/auth/login"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk());
    }

    @Test
    void nonHealthActuatorEndpointsAreDenied() throws Exception {
        mockMvc.perform(get("/actuator/info")
                        .header("Authorization", "Bearer owner"))
                .andExpect(status().isForbidden());
    }

    @ParameterizedTest
    @ValueSource(strings = {"owner", "admin", "operator", "viewer"})
    void everyRecognizedRoleCanReadApiResources(String token)
            throws Exception {
        mockMvc.perform(get("/api/orders")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/recipes")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
        mockMvc.perform(get(
                        "/api/recommendations/recommendation-1/explanation")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
    }

    @Test
    void anonymousApiReadRequiresAuthentication() throws Exception {
        mockMvc.perform(get("/api/orders"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void configuredPreflightDoesNotRequireAuthentication() throws Exception {
        String origin =
                "https://bizlama-api-12345.asia-south1.run.app";

        mockMvc.perform(options("/api/receipts")
                        .header("Origin", origin)
                        .header(
                                "Access-Control-Request-Method",
                                "POST"
                        ))
                .andExpect(status().isOk())
                .andExpect(header().string(
                        "Access-Control-Allow-Origin",
                        origin
                ));
    }

    @Test
    void kitchenOperatorCanWriteOrdinaryOperations() throws Exception {
        mockMvc.perform(post("/api/orders")
                        .header("Authorization", "Bearer operator"))
                .andExpect(status().isOk());
    }

    @Test
    void viewerCannotWriteAndUnknownRolesCannotMutate() throws Exception {
        mockMvc.perform(post("/api/orders")
                        .header("Authorization", "Bearer viewer"))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/orders")
                        .header("Authorization", "Bearer unknown"))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/orders")
                        .header("Authorization", "Bearer missing-role"))
                .andExpect(status().isForbidden());
    }

    @Test
    void kitchenOperatorCannotPerformHighRiskWrites() throws Exception {
        for (String path : HIGH_RISK_PATHS) {
            mockMvc.perform(post(path)
                            .header("Authorization", "Bearer operator"))
                    .andExpect(status().isForbidden());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"owner", "admin"})
    void ownerAndAdminCanPerformHighRiskWrites(String token)
            throws Exception {
        for (String path : HIGH_RISK_PATHS) {
            mockMvc.perform(post(path)
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk());
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableWebMvc
    @EnableWebSecurity
    static class TestConfiguration {

        @Bean
        SecurityFilterChain securityFilterChain(
                HttpSecurity http,
                JwtAuthenticationConverter jwtAuthenticationConverter,
                WorkspaceAccessPolicy workspaceAccessPolicy
        ) throws Exception {
            return new SecurityConfig().securityFilterChain(
                    http,
                    jwtAuthenticationConverter,
                    workspaceAccessPolicy
            );
        }

        @Bean
        JwtAuthenticationConverter jwtAuthenticationConverter(
                WorkspaceAccessPolicy workspaceAccessPolicy
        ) {
            return new SecurityConfig().jwtAuthenticationConverter(
                    workspaceAccessPolicy
            );
        }

        @Bean
        WorkspaceAccessPolicy workspaceAccessPolicy() {
            WorkspaceAccessPolicy policy = mock(WorkspaceAccessPolicy.class);
            when(policy.effectiveRole(any(Jwt.class))).thenAnswer(invocation -> {
                String role = invocation.getArgument(0, Jwt.class)
                        .getClaimAsString("role");
                return switch (role == null ? "" : role) {
                    case "OWNER", "ADMIN", "KITCHEN_OPERATOR", "VIEWER" ->
                            Optional.of(role);
                    default -> Optional.empty();
                };
            });
            return policy;
        }

        @Bean
        JwtDecoder jwtDecoder() {
            return token -> {
                String role = switch (token) {
                    case "owner" -> "OWNER";
                    case "admin" -> "ADMIN";
                    case "operator" -> "KITCHEN_OPERATOR";
                    case "viewer" -> "VIEWER";
                    case "unknown" -> "CHEF";
                    default -> null;
                };

                Jwt.Builder jwt = Jwt.withTokenValue(token)
                        .header("alg", "none")
                        .subject("user-123");

                if (role != null) {
                    jwt.claim("role", role);
                }

                return jwt.build();
            };
        }

        @Bean
        PolicyProbeController policyProbeController() {
            return new PolicyProbeController();
        }
    }

    @RestController
    static class PolicyProbeController {

        @GetMapping({
                "/api/orders",
                "/api/recipes",
                "/api/recommendations/recommendation-1/explanation"
        })
        String read() {
            return "ok";
        }

        @PostMapping({
                "/api/orders",
                "/api/receipts",
                "/api/recipes/dishes",
                "/api/experiments/experiment-1/approve",
                "/api/stock/purchases",
                "/api/events/confirm"
        })
        String write() {
            return "ok";
        }

        @GetMapping({
                "/api/auth/config",
                "/actuator/health",
                "/actuator/info"
        })
        String publicRead() {
            return "ok";
        }

        @PostMapping("/api/auth/login")
        String publicWrite() {
            return "ok";
        }
    }
}
