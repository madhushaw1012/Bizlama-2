package com.bizlama.api.auth;

import com.bizlama.api.config.WorkspaceProperties;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;

/**
 * Authoritative business-workspace boundary for authenticated identities.
 *
 * <p>An active {@code business_users} row is authoritative for both workspace
 * membership and role. Token role claims are ignored except for the deliberately
 * narrow membership-free local demo owner issued by {@link AuthController}.</p>
 */
@Service
public class WorkspaceAccessPolicy {

    private static final Set<String> RECOGNIZED_ROLES = Set.of(
            "OWNER",
            "ADMIN",
            "KITCHEN_OPERATOR",
            "VIEWER"
    );

    private final JdbcClient jdbc;
    private final AuthProperties auth;
    private final WorkspaceProperties workspace;

    public WorkspaceAccessPolicy(
            JdbcClient jdbc,
            AuthProperties auth,
            WorkspaceProperties workspace
    ) {
        this.jdbc = jdbc;
        this.auth = auth;
        this.workspace = workspace;
    }

    public String authorizeConfiguredWorkspace(Jwt jwt) {
        return authorizeKitchen(jwt, workspace.kitchenId());
    }

    public Optional<WorkspaceIdentity> configuredIdentity(Jwt jwt) {
        if (jwt == null || jwt.getSubject() == null
                || jwt.getSubject().isBlank()) {
            return Optional.empty();
        }
        String kitchenId = workspace.kitchenId();
        if (!tokenAllowsKitchen(jwt, kitchenId)) {
            return Optional.empty();
        }
        return resolveIdentity(jwt, jwt.getSubject(), kitchenId);
    }

    public Optional<String> effectiveRole(Jwt jwt) {
        return configuredIdentity(jwt).map(WorkspaceIdentity::role);
    }

    public String authorizeKitchen(Jwt jwt, String requestedKitchenId) {
        String subject = authenticatedSubject(jwt);
        if (requestedKitchenId == null || requestedKitchenId.isBlank()) {
            throw new AccessDeniedException("Workspace scope is required.");
        }

        String requested = requestedKitchenId.trim();
        if (!workspace.kitchenId().equals(requested)) {
            throw new AccessDeniedException(
                    "Kitchen is outside this deployed workspace."
            );
        }

        if (!tokenAllowsKitchen(jwt, requested)) {
            throw new AccessDeniedException(
                    "Kitchen scope does not match the token."
            );
        }

        if (resolveIdentity(jwt, subject, requested).isPresent()) {
            return requested;
        }

        throw new AccessDeniedException(
                "Authenticated user is not an active member of this workspace."
        );
    }

    private Optional<WorkspaceIdentity> resolveIdentity(
            Jwt jwt,
            String subject,
            String kitchenId
    ) {
        Optional<WorkspaceRow> workspaceRow = jdbc.sql("""
                        SELECT kitchen.active AS kitchen_active,
                               business_user.id AS business_user_id,
                               business_user.email,
                               business_user.display_name,
                               business_user.role,
                               business_user.active AS business_user_active
                        FROM kitchens kitchen
                        LEFT JOIN business_users business_user
                          ON business_user.kitchen_id = kitchen.id
                         AND business_user.identity_subject = :subject
                        WHERE kitchen.id = :kitchen
                        """)
                .param("subject", subject)
                .param("kitchen", kitchenId)
                .query((rs, ignored) -> new WorkspaceRow(
                        rs.getBoolean("kitchen_active"),
                        rs.getString("business_user_id"),
                        rs.getString("email"),
                        rs.getString("display_name"),
                        rs.getString("role"),
                        rs.getBoolean("business_user_active")
                ))
                .optional();
        if (workspaceRow.isEmpty() || !workspaceRow.get().kitchenActive()) {
            return Optional.empty();
        }

        WorkspaceRow row = workspaceRow.get();
        if (row.businessUserId() != null) {
            if (!row.businessUserActive()) {
                return Optional.empty();
            }
            Optional<String> role = normalizeRole(row.role());
            if (role.isEmpty() || row.email() == null || row.email().isBlank()
                    || row.displayName() == null || row.displayName().isBlank()) {
                return Optional.empty();
            }
            return Optional.of(new WorkspaceIdentity(
                    subject,
                    row.email(),
                    row.displayName(),
                    role.get()
            ));
        }

        if (isLocalDemoOwner(jwt, subject, kitchenId)) {
            return Optional.of(new WorkspaceIdentity(
                    subject,
                    auth.ownerEmail(),
                    auth.ownerName(),
                    "OWNER"
            ));
        }
        return Optional.empty();
    }

    private String authenticatedSubject(Jwt jwt) {
        if (jwt == null || jwt.getSubject() == null
                || jwt.getSubject().isBlank()) {
            throw new AccessDeniedException(
                    "Authenticated subject is required."
            );
        }
        return jwt.getSubject();
    }

    private boolean isLocalDemoOwner(
            Jwt jwt,
            String subject,
            String requestedKitchenId
    ) {
        return !auth.identityPlatform()
                && workspace.kitchenId().equals(requestedKitchenId)
                && "bizlama-local".equals(jwt.getClaimAsString("iss"))
                && auth.ownerEmail().equals(subject)
                && "OWNER".equals(jwt.getClaimAsString("role"));
    }

    private boolean tokenAllowsKitchen(Jwt jwt, String kitchenId) {
        String tokenKitchen = firstNonBlank(
                jwt.getClaimAsString("kitchen_id"),
                jwt.getClaimAsString("kitchenId")
        );
        return tokenKitchen == null || kitchenId.equals(tokenKitchen);
    }

    private static Optional<String> normalizeRole(String role) {
        if (role == null || role.isBlank()) {
            return Optional.empty();
        }
        String normalized = role.trim().toUpperCase(Locale.ROOT);
        return RECOGNIZED_ROLES.contains(normalized)
                ? Optional.of(normalized)
                : Optional.empty();
    }

    private static String firstNonBlank(String first, String second) {
        if (first != null && !first.isBlank()) {
            return first.trim();
        }
        return second == null || second.isBlank() ? null : second.trim();
    }

    public record WorkspaceIdentity(
            String subject,
            String email,
            String displayName,
            String role
    ) {
    }

    private record WorkspaceRow(
            boolean kitchenActive,
            String businessUserId,
            String email,
            String displayName,
            String role,
            boolean businessUserActive
    ) {
    }
}
