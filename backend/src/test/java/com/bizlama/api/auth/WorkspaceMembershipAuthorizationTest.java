package com.bizlama.api.auth;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.bizlama.api.config.WorkspaceProperties;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mock.web.MockServletContext;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
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

class WorkspaceMembershipAuthorizationTest {

    private AnnotationConfigWebApplicationContext context;
    private MockMvc mockMvc;
    private JdbcClient jdbc;

    @BeforeEach
    void setUp() {
        context = new AnnotationConfigWebApplicationContext();
        context.setServletContext(new MockServletContext());
        context.register(TestConfiguration.class);
        context.refresh();

        jdbc = context.getBean(JdbcClient.class);
        jdbc.sql("""
                CREATE TABLE kitchens (
                  id VARCHAR(80) PRIMARY KEY,
                  active BOOLEAN NOT NULL
                )
                """).update();
        jdbc.sql("""
                CREATE TABLE business_users (
                  id VARCHAR(100) PRIMARY KEY,
                  kitchen_id VARCHAR(80) NOT NULL,
                  identity_subject VARCHAR(200) NOT NULL,
                  email VARCHAR(320) NOT NULL,
                  display_name VARCHAR(160) NOT NULL,
                  role VARCHAR(40) NOT NULL,
                  active BOOLEAN NOT NULL,
                  UNIQUE (kitchen_id, identity_subject),
                  FOREIGN KEY (kitchen_id) REFERENCES kitchens(id)
                )
                """).update();
        jdbc.sql("""
                INSERT INTO kitchens (id, active)
                VALUES ('kitchen-default', TRUE), ('kitchen-other', TRUE)
                """).update();

        mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(context.getBean(FilterChainProxy.class))
                .build();
    }

    @AfterEach
    void tearDown() {
        context.close();
    }

    @Test
    void activeConfiguredWorkspaceMemberCanReadOperationalApi()
            throws Exception {
        addMember(
                "member-1",
                "kitchen-default",
                "firebase-member",
                "VIEWER",
                true
        );

        mockMvc.perform(get("/api/orders")
                        .header("Authorization", "Bearer member"))
                .andExpect(status().isOk())
                .andExpect(content().string("kitchen-default"));
        mockMvc.perform(get("/api/auth/me")
                        .header("Authorization", "Bearer member"))
                .andExpect(status().isOk())
                .andExpect(content().string("VIEWER"));
    }

    @Test
    void roleBearingIdentityWithoutMembershipIsForbidden()
            throws Exception {
        mockMvc.perform(get("/api/orders")
                        .header("Authorization", "Bearer non-member"))
                .andExpect(status().isForbidden());
    }

    @Test
    void exactLocalDemoOwnerRemainsUsableWithoutMembership()
            throws Exception {
        mockMvc.perform(get("/api/orders")
                        .header("Authorization", "Bearer local-owner"))
                .andExpect(status().isOk())
                .andExpect(content().string("kitchen-default"));
    }

    @Test
    void databaseRoleDowngradeOverridesTokenImmediately() throws Exception {
        addMember(
                "member-downgrade",
                "kitchen-default",
                "firebase-downgrade",
                "OWNER",
                true
        );

        mockMvc.perform(post("/api/stock/purchases")
                        .header("Authorization", "Bearer downgrade"))
                .andExpect(status().isOk());

        jdbc.sql("""
                        UPDATE business_users
                        SET role = 'VIEWER'
                        WHERE id = 'member-downgrade'
                        """).update();

        mockMvc.perform(get("/api/auth/me")
                        .header("Authorization", "Bearer downgrade"))
                .andExpect(status().isOk())
                .andExpect(content().string("VIEWER"));
        mockMvc.perform(get("/api/orders")
                        .header("Authorization", "Bearer downgrade"))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/stock/purchases")
                        .header("Authorization", "Bearer downgrade"))
                .andExpect(status().isForbidden());
    }

    @Test
    void otherKitchenMembershipAndTokenCannotCrossDeploymentBoundary()
            throws Exception {
        addMember(
                "member-other",
                "kitchen-other",
                "firebase-other-member",
                "VIEWER",
                true
        );

        mockMvc.perform(get("/api/orders")
                        .header("Authorization", "Bearer cross-kitchen"))
                .andExpect(status().isForbidden());
    }

    @Test
    void inactiveMembershipIsForbidden() throws Exception {
        addMember(
                "member-inactive",
                "kitchen-default",
                "firebase-inactive",
                "OWNER",
                false
        );

        mockMvc.perform(get("/api/orders")
                        .header("Authorization", "Bearer inactive"))
                .andExpect(status().isForbidden());
    }

    @Test
    void authAndHealthRoutesRemainOutsideWorkspaceGuard()
            throws Exception {
        mockMvc.perform(get("/api/auth/config"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/auth/me")
                        .header("Authorization", "Bearer non-member"))
                .andExpect(status().isOk())
                .andExpect(content().string("UNASSIGNED"));
        mockMvc.perform(get("/api/auth/me"))
                .andExpect(status().isUnauthorized());
    }

    private void addMember(
            String id,
            String kitchenId,
            String subject,
            String role,
            boolean active
    ) {
        jdbc.sql("""
                        INSERT INTO business_users (
                          id,
                          kitchen_id,
                          identity_subject,
                          email,
                          display_name,
                          role,
                          active
                        ) VALUES (
                          :id,
                          :kitchen,
                          :subject,
                          :email,
                          :displayName,
                          :role,
                          :active
                        )
                        """)
                .param("id", id)
                .param("kitchen", kitchenId)
                .param("subject", subject)
                .param("email", subject + "@example.test")
                .param("displayName", subject)
                .param("role", role)
                .param("active", active)
                .update();
    }

    @Configuration(proxyBeanMethods = false)
    @EnableWebMvc
    @EnableWebSecurity
    static class TestConfiguration {

        @Bean
        DataSource dataSource() {
            return new DriverManagerDataSource(
                    "jdbc:h2:mem:workspace-membership-"
                            + UUID.randomUUID()
                            + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE"
                            + ";DB_CLOSE_DELAY=-1",
                    "sa",
                    ""
            );
        }

        @Bean
        JdbcClient jdbcClient(DataSource dataSource) {
            return JdbcClient.create(dataSource);
        }

        @Bean
        AuthProperties authProperties() {
            return new AuthProperties(
                    "local",
                    "owner@bizlama.local",
                    "Local owner",
                    "local-password",
                    "test-token-secret-that-is-long-enough",
                    8L,
                    "test-project",
                    null
            );
        }

        @Bean
        WorkspaceProperties workspaceProperties() {
            WorkspaceProperties workspace = new WorkspaceProperties();
            workspace.setKitchenId("kitchen-default");
            workspace.setLocationId("location-main");
            workspace.setTimeZone("UTC");
            return workspace;
        }

        @Bean
        WorkspaceAccessPolicy workspaceAccessPolicy(
                JdbcClient jdbc,
                AuthProperties auth,
                WorkspaceProperties workspace
        ) {
            return new WorkspaceAccessPolicy(jdbc, auth, workspace);
        }

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
        JwtDecoder jwtDecoder() {
            return token -> switch (token) {
                case "member" -> jwt(
                        token,
                        "firebase-member",
                        "https://securetoken.google.com/test-project",
                        null,
                        null
                );
                case "non-member" -> jwt(
                        token,
                        "firebase-non-member",
                        "https://securetoken.google.com/test-project",
                        "OWNER",
                        null
                );
                case "local-owner" -> jwt(
                        token,
                        "owner@bizlama.local",
                        "bizlama-local",
                        "OWNER",
                        null
                );
                case "cross-kitchen" -> jwt(
                        token,
                        "firebase-other-member",
                        "https://securetoken.google.com/test-project",
                        "VIEWER",
                        "kitchen-other"
                );
                case "inactive" -> jwt(
                        token,
                        "firebase-inactive",
                        "https://securetoken.google.com/test-project",
                        null,
                        null
                );
                case "downgrade" -> jwt(
                        token,
                        "firebase-downgrade",
                        "https://securetoken.google.com/test-project",
                        "OWNER",
                        null
                );
                default -> throw new IllegalArgumentException("Unknown token");
            };
        }

        @Bean
        PolicyProbeController policyProbeController(
                WorkspaceProperties workspace,
                WorkspaceAccessPolicy workspaceAccessPolicy
        ) {
            return new PolicyProbeController(workspace, workspaceAccessPolicy);
        }

        private Jwt jwt(
                String token,
                String subject,
                String issuer,
                String role,
                String kitchenId
        ) {
            Jwt.Builder builder = Jwt.withTokenValue(token)
                    .header("alg", "none")
                    .subject(subject)
                    .claim("iss", issuer);
            if (role != null) {
                builder.claim("role", role);
            }
            if (kitchenId != null) {
                builder.claim("kitchen_id", kitchenId);
            }
            return builder.build();
        }
    }

    @RestController
    static class PolicyProbeController {

        private final WorkspaceProperties workspace;
        private final WorkspaceAccessPolicy workspaceAccessPolicy;

        PolicyProbeController(
                WorkspaceProperties workspace,
                WorkspaceAccessPolicy workspaceAccessPolicy
        ) {
            this.workspace = workspace;
            this.workspaceAccessPolicy = workspaceAccessPolicy;
        }

        @GetMapping("/api/orders")
        String operationalData() {
            return workspace.kitchenId();
        }

        @PostMapping("/api/stock/purchases")
        String highRiskWrite() {
            return "ok";
        }

        @GetMapping({"/api/auth/config", "/actuator/health"})
        String nonOperationalRoute() {
            return "ok";
        }

        @GetMapping("/api/auth/me")
        String authenticatedProfile(@AuthenticationPrincipal Jwt jwt) {
            return workspaceAccessPolicy.effectiveRole(jwt)
                    .orElse("UNASSIGNED");
        }
    }
}
