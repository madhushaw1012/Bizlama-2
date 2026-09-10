package com.bizlama.api.recommendations;

import com.bizlama.api.auth.WorkspaceAccessPolicy;
import com.bizlama.api.config.WorkspaceProperties;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;

/** Resolves requested recommendation scope through the shared workspace policy. */
@Service
public class KitchenAccessService {

    private final WorkspaceAccessPolicy workspaceAccess;
    private final WorkspaceProperties workspace;

    public KitchenAccessService(
            WorkspaceAccessPolicy workspaceAccess,
            WorkspaceProperties workspace
    ) {
        this.workspaceAccess = workspaceAccess;
        this.workspace = workspace;
    }

    public String authorizeKitchen(Jwt jwt, String requestedKitchenId) {
        if (jwt == null || jwt.getSubject() == null
                || jwt.getSubject().isBlank()) {
            throw new AccessDeniedException(
                    "Authenticated subject is required."
            );
        }
        if (requestedKitchenId == null || requestedKitchenId.isBlank()) {
            throw new DemandValidationException("Kitchen is required.");
        }

        return workspaceAccess.authorizeKitchen(jwt, requestedKitchenId);
    }

    public String authorizeLocation(String requestedLocationId) {
        if (requestedLocationId == null || requestedLocationId.isBlank()) {
            throw new DemandValidationException("Location is required.");
        }
        String requested = requestedLocationId.trim();
        if (!workspace.locationId().equals(requested)) {
            throw new AccessDeniedException(
                    "Location is outside this deployed workspace."
            );
        }
        return requested;
    }

    public String actor(Jwt jwt) {
        if (jwt == null || jwt.getSubject() == null || jwt.getSubject().isBlank()) {
            throw new AccessDeniedException("Authenticated subject is required.");
        }
        return jwt.getSubject();
    }

    public void requireOwnerOrAdmin(Jwt jwt) {
        String role = workspaceAccess.effectiveRole(jwt)
                .orElse(null);
        if (!"OWNER".equals(role) && !"ADMIN".equals(role)) {
            throw new AccessDeniedException(
                    "Owner or admin approval is required for this action."
            );
        }
    }
}
