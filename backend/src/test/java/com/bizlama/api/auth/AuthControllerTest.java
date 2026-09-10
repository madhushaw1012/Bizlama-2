package com.bizlama.api.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtEncoder;

class AuthControllerTest {

    private final WorkspaceAccessPolicy accessPolicy =
            mock(WorkspaceAccessPolicy.class);
    private final AuthController controller = new AuthController(
            mock(AuthProperties.class),
            mock(PasswordEncoder.class),
            jwtEncoderProvider(),
            accessPolicy
    );

    @Test
    void meReturnsDatabaseIdentityForTokenWithoutRoleClaim() {
        Jwt jwt = Jwt.withTokenValue("token")
                .header("alg", "none")
                .subject("firebase-user-id")
                .claim("email", "admin@example.com")
                .build();
        when(accessPolicy.configuredIdentity(jwt)).thenReturn(Optional.of(
                new WorkspaceAccessPolicy.WorkspaceIdentity(
                        "firebase-user-id",
                        "operator@example.com",
                        "Kitchen operator",
                        "KITCHEN_OPERATOR"
                )
        ));

        assertThat(controller.me(jwt)).isEqualTo(new AuthController.User(
                "operator@example.com",
                "Kitchen operator",
                "KITCHEN_OPERATOR"
        ));
    }

    @Test
    void meNeverEchoesAnUnresolvedElevatedTokenRole() {
        Jwt jwt = Jwt.withTokenValue("token")
                .header("alg", "none")
                .subject("operator@example.com")
                .claim("role", "OWNER")
                .build();
        when(accessPolicy.configuredIdentity(jwt)).thenReturn(Optional.empty());

        assertThat(controller.me(jwt)).isEqualTo(new AuthController.User(
                "operator@example.com",
                "operator@example.com",
                "UNASSIGNED"
        ));
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<JwtEncoder> jwtEncoderProvider() {
        return mock(ObjectProvider.class);
    }
}
