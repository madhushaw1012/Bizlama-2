package com.bizlama.api.recommendations;

import com.bizlama.api.recommendations.DemandCalculation.DemandScope;
import com.bizlama.api.recommendations.DemandCalculation.SafetyStockSetting;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/demand")
public class DemandController {

    private final DemandCalculationService demand;
    private final KitchenAccessService access;

    public DemandController(
            DemandCalculationService demand,
            KitchenAccessService access
    ) {
        this.demand = demand;
        this.access = access;
    }

    @PostMapping("/calculate")
    public DemandCalculation calculate(
            @Valid @RequestBody CalculationRequest request,
            @AuthenticationPrincipal Jwt jwt
    ) {
        String kitchenId = access.authorizeKitchen(jwt, request.kitchenId());
        Instant asOf = request.asOf() == null ? Instant.now() : request.asOf();
        return demand.calculate(new DemandScope(
                kitchenId,
                access.authorizeLocation(request.locationId()),
                request.horizonStart(),
                request.horizonEnd(),
                asOf
        ));
    }

    @GetMapping("/safety-stock")
    public List<SafetyStockSetting> safetyStock(
            @RequestParam String kitchenId,
            @RequestParam String locationId,
            @AuthenticationPrincipal Jwt jwt
    ) {
        return demand.safetyStock(
                access.authorizeKitchen(jwt, kitchenId),
                access.authorizeLocation(locationId)
        );
    }

    @PutMapping("/safety-stock/{ingredientId}")
    public SafetyStockSetting configureSafetyStock(
            @PathVariable String ingredientId,
            @Valid @RequestBody SafetyStockRequest request,
            @AuthenticationPrincipal Jwt jwt
    ) {
        access.requireOwnerOrAdmin(jwt);
        String kitchenId = access.authorizeKitchen(jwt, request.kitchenId());
        return demand.configureSafetyStock(
                kitchenId,
                access.authorizeLocation(request.locationId()),
                ingredientId,
                request.quantity(),
                request.unit(),
                request.expectedVersion(),
                access.actor(jwt)
        );
    }

    public record CalculationRequest(
            @NotBlank String kitchenId,
            @NotBlank String locationId,
            @NotNull Instant horizonStart,
            @NotNull Instant horizonEnd,
            Instant asOf
    ) {
    }

    public record SafetyStockRequest(
            @NotBlank String kitchenId,
            @NotBlank String locationId,
            @NotNull @DecimalMin(value = "0.0", inclusive = true) BigDecimal quantity,
            @NotBlank String unit,
            @PositiveOrZero Integer expectedVersion
    ) {
    }
}
