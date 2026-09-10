package com.bizlama.api.recommendations;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.bizlama.api.auth.WorkspaceAccessPolicy;
import com.bizlama.api.config.WorkspaceProperties;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.oauth2.jwt.Jwt;

class KitchenAccessServiceTest {

    private WorkspaceAccessPolicy workspaceAccess;
    private KitchenAccessService access;

    @BeforeEach
    void setUp() {
        workspaceAccess = mock(WorkspaceAccessPolicy.class);
        access = new KitchenAccessService(
                workspaceAccess,
                new WorkspaceProperties()
        );
    }

    @ParameterizedTest
    @ValueSource(strings = {"OWNER", "ADMIN"})
    void acceptsAuthoritativeDatabaseRoleWithoutTokenRoleClaim(String role) {
        Jwt jwt = claimlessJwt();
        when(workspaceAccess.effectiveRole(jwt)).thenReturn(Optional.of(role));

        assertThatCode(() -> access.requireOwnerOrAdmin(jwt))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsElevatedTokenClaimWhenDatabaseRoleIsViewer() {
        Jwt jwt = Jwt.withTokenValue("token")
                .header("alg", "none")
                .subject("user-123")
                .claim("role", "OWNER")
                .build();
        when(workspaceAccess.effectiveRole(jwt))
                .thenReturn(Optional.of("VIEWER"));

        assertThatThrownBy(() -> access.requireOwnerOrAdmin(jwt))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void rejectsIdentityWithoutAnEffectiveWorkspaceRole() {
        Jwt jwt = claimlessJwt();
        when(workspaceAccess.effectiveRole(jwt)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> access.requireOwnerOrAdmin(jwt))
                .isInstanceOf(AccessDeniedException.class);
    }

    private Jwt claimlessJwt() {
        return Jwt.withTokenValue("token")
                .header("alg", "none")
                .subject("user-123")
                .build();
    }
}
