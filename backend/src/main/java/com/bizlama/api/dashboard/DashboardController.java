package com.bizlama.api.dashboard;

import com.bizlama.api.config.WorkspaceProperties;
import com.bizlama.api.recommendations.KitchenAccessService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/dashboard")
public class DashboardController {

    private final DemoDashboardService dashboard;
    private final KitchenAccessService access;
    private final WorkspaceProperties workspace;

    public DashboardController(
            DemoDashboardService dashboard,
            KitchenAccessService access,
            WorkspaceProperties workspace
    ) {
        this.dashboard = dashboard;
        this.access = access;
        this.workspace = workspace;
    }

    @GetMapping
    public DashboardResponse getDashboard(
            @AuthenticationPrincipal Jwt jwt
    ) {
        String kitchenId = access.authorizeKitchen(
                jwt,
                workspace.kitchenId()
        );
        return dashboard.getDashboard(
                kitchenId,
                workspace.locationId()
        );
    }
}
