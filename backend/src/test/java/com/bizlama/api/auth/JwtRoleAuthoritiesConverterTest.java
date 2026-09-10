package com.bizlama.api.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

class JwtRoleAuthoritiesConverterTest {

    private final WorkspaceAccessPolicy accessPolicy =
            mock(WorkspaceAccessPolicy.class);
    private final JwtRoleAuthoritiesConverter converter =
            new JwtRoleAuthoritiesConverter(accessPolicy);

    @Test
    void mapsEffectiveDatabaseRoleWhenTokenHasNoRoleClaim() {
        Jwt jwt = jwtWithoutRole();
        when(accessPolicy.effectiveRole(jwt))
                .thenReturn(Optional.of("KITCHEN_OPERATOR"));

        assertThat(converter.convert(jwt))
                .extracting(GrantedAuthority::getAuthority)
                .containsExactly("ROLE_KITCHEN_OPERATOR");
    }

    @Test
    void databaseRoleOverridesElevatedTokenClaim() {
        Jwt jwt = jwtWithRole("OWNER");
        when(accessPolicy.effectiveRole(jwt))
                .thenReturn(Optional.of("VIEWER"));

        assertThat(converter.convert(jwt))
                .extracting(GrantedAuthority::getAuthority)
                .containsExactly("ROLE_VIEWER");
    }

    @Test
    void grantsNoAuthorityWithoutEffectiveWorkspaceIdentity() {
        Jwt jwt = jwtWithRole("OWNER");
        when(accessPolicy.effectiveRole(jwt)).thenReturn(Optional.empty());

        assertThat(converter.convert(jwt)).isEmpty();
    }

    private Jwt jwtWithRole(Object role) {
        return Jwt.withTokenValue("token")
                .header("alg", "none")
                .subject("user-123")
                .claim("role", role)
                .build();
    }

    private Jwt jwtWithoutRole() {
        return Jwt.withTokenValue("token")
                .header("alg", "none")
                .subject("user-123")
                .build();
    }
}
