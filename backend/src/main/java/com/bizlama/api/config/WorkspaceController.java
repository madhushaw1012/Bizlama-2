package com.bizlama.api.config;

import com.bizlama.api.recommendations.KitchenAccessService;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Authenticated operational scope used by same-origin clients. */
@RestController
@RequestMapping("/api/workspace")
public class WorkspaceController {

    private final WorkspaceProperties workspace;
    private final KitchenAccessService access;
    private final JdbcClient jdbc;

    public WorkspaceController(
            WorkspaceProperties workspace,
            KitchenAccessService access,
            JdbcClient jdbc
    ) {
        this.workspace = workspace;
        this.access = access;
        this.jdbc = jdbc;
    }

    @GetMapping
    public WorkspaceView current(@AuthenticationPrincipal Jwt jwt) {
        String kitchenId = access.authorizeKitchen(
                jwt,
                workspace.kitchenId()
        );
        String locationId = access.authorizeLocation(workspace.locationId());
        WorkspaceNames names = jdbc.sql("""
                        SELECT k.name AS kitchen_name,
                               k.currency,
                               l.name AS location_name
                        FROM kitchens k
                        JOIN kitchen_locations l
                          ON l.kitchen_id = k.id
                        WHERE k.id = :kitchen
                          AND l.id = :location
                          AND k.active = TRUE
                          AND l.active = TRUE
                        """)
                .param("kitchen", kitchenId)
                .param("location", locationId)
                .query((result, rowNumber) -> new WorkspaceNames(
                        result.getString("kitchen_name"),
                        result.getString("location_name"),
                        result.getString("currency")))
                .single();
        return new WorkspaceView(
                kitchenId,
                names.kitchenName(),
                locationId,
                names.locationName(),
                workspace.zoneId().getId(),
                names.currency()
        );
    }

    private record WorkspaceNames(
            String kitchenName,
            String locationName,
            String currency
    ) {
    }

    public record WorkspaceView(
            String kitchenId,
            String kitchenName,
            String locationId,
            String locationName,
            String timeZone,
            String currency
    ) {
    }
}
