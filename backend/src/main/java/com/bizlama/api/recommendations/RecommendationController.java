package com.bizlama.api.recommendations;

import com.bizlama.api.recommendations.DemandCalculation.DemandScope;
import com.bizlama.api.recommendations.GovernedRecommendation.GenerationResult;
import com.bizlama.api.recommendations.GovernedRecommendation.Outcome;
import com.bizlama.api.recommendations.GovernedRecommendation.OutcomeType;
import com.bizlama.api.recommendations.GovernedRecommendation.Status;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/recommendations")
public class RecommendationController {

    private final RecommendationService recommendations;
    private final KitchenAccessService access;

    public RecommendationController(
            RecommendationService recommendations,
            KitchenAccessService access
    ) {
        this.recommendations = recommendations;
        this.access = access;
    }

    @PostMapping("/generate")
    @ResponseStatus(HttpStatus.CREATED)
    public GenerationResult generate(
            @Valid @RequestBody GenerateRequest request,
            @AuthenticationPrincipal Jwt jwt
    ) {
        String kitchenId = access.authorizeKitchen(jwt, request.kitchenId());
        Instant asOf = Instant.now();
        return recommendations.generate(
                new DemandScope(
                        kitchenId,
                        access.authorizeLocation(request.locationId()),
                        request.horizonStart(),
                        request.horizonEnd(),
                        asOf
                ),
                request.expiresAt(),
                access.actor(jwt)
        );
    }

    @GetMapping
    public List<GovernedRecommendation> list(
            @RequestParam String kitchenId,
            @RequestParam String locationId,
            @RequestParam(required = false) Status status,
            @AuthenticationPrincipal Jwt jwt
    ) {
        return recommendations.recommendations(
                access.authorizeKitchen(jwt, kitchenId),
                access.authorizeLocation(locationId),
                status
        );
    }

    @GetMapping("/{id}")
    public GovernedRecommendation get(
            @PathVariable String id,
            @AuthenticationPrincipal Jwt jwt
    ) {
        GovernedRecommendation value = recommendations.recommendation(id);
        access.authorizeKitchen(jwt, value.kitchenId());
        access.authorizeLocation(value.locationId());
        return value;
    }

    @GetMapping("/{id}/calculation")
    public DemandCalculation calculation(
            @PathVariable String id,
            @AuthenticationPrincipal Jwt jwt
    ) {
        GovernedRecommendation value = recommendations.recommendation(id);
        access.authorizeKitchen(jwt, value.kitchenId());
        access.authorizeLocation(value.locationId());
        return recommendations.calculation(id);
    }

    @PostMapping("/{id}/approve")
    public GovernedRecommendation approve(
            @PathVariable String id,
            @Valid @RequestBody DecisionRequest request,
            @AuthenticationPrincipal Jwt jwt
    ) {
        GovernedRecommendation value = authorize(id, jwt);
        access.requireOwnerOrAdmin(jwt);
        return recommendations.approve(
                value.id(),
                request.expectedVersion(),
                access.actor(jwt),
                request.reason()
        );
    }

    @PostMapping("/{id}/edit")
    public GovernedRecommendation edit(
            @PathVariable String id,
            @Valid @RequestBody EditRequest request,
            @AuthenticationPrincipal Jwt jwt
    ) {
        GovernedRecommendation value = authorize(id, jwt);
        return recommendations.edit(
                value.id(),
                request.expectedVersion(),
                request.proposedQuantity(),
                access.actor(jwt),
                request.reason()
        );
    }

    @PostMapping("/{id}/dismiss")
    public GovernedRecommendation dismiss(
            @PathVariable String id,
            @Valid @RequestBody DecisionRequest request,
            @AuthenticationPrincipal Jwt jwt
    ) {
        GovernedRecommendation value = authorize(id, jwt);
        return recommendations.dismiss(
                value.id(),
                request.expectedVersion(),
                access.actor(jwt),
                request.reason()
        );
    }

    @PostMapping("/{id}/flag-inventory")
    public GovernedRecommendation flagInventory(
            @PathVariable String id,
            @Valid @RequestBody DecisionRequest request,
            @AuthenticationPrincipal Jwt jwt
    ) {
        GovernedRecommendation value = authorize(id, jwt);
        return recommendations.flagInventory(
                value.id(),
                request.expectedVersion(),
                access.actor(jwt),
                request.reason()
        );
    }

    @PostMapping("/{id}/apply")
    public GovernedRecommendation markApplied(
            @PathVariable String id,
            @Valid @RequestBody ApplyRequest request,
            @AuthenticationPrincipal Jwt jwt
    ) {
        GovernedRecommendation value = authorize(id, jwt);
        access.requireOwnerOrAdmin(jwt);
        return recommendations.markApplied(
                value.id(),
                request.expectedVersion(),
                access.actor(jwt),
                request.reason()
        );
    }

    @PostMapping("/{id}/outcomes")
    @ResponseStatus(HttpStatus.CREATED)
    public Outcome recordOutcome(
            @PathVariable String id,
            @Valid @RequestBody OutcomeRequest request,
            @AuthenticationPrincipal Jwt jwt
    ) {
        GovernedRecommendation value = authorize(id, jwt);
        return recommendations.recordOutcome(
                value.id(),
                request.expectedVersion(),
                request.type(),
                request.quantity(),
                request.unit(),
                request.sourceReferenceType(),
                request.sourceReferenceId(),
                request.notes(),
                request.occurredAt(),
                access.actor(jwt)
        );
    }

    @GetMapping("/{id}/outcomes")
    public List<Outcome> outcomes(
            @PathVariable String id,
            @AuthenticationPrincipal Jwt jwt
    ) {
        GovernedRecommendation value = authorize(id, jwt);
        return recommendations.outcomes(value.id());
    }

    @PostMapping("/{id}/reverse")
    public GovernedRecommendation reverse(
            @PathVariable String id,
            @Valid @RequestBody DecisionRequest request,
            @AuthenticationPrincipal Jwt jwt
    ) {
        GovernedRecommendation value = authorize(id, jwt);
        access.requireOwnerOrAdmin(jwt);
        return recommendations.reverse(
                value.id(),
                request.expectedVersion(),
                access.actor(jwt),
                request.reason()
        );
    }

    private GovernedRecommendation authorize(String id, Jwt jwt) {
        GovernedRecommendation value = recommendations.recommendation(id);
        access.authorizeKitchen(jwt, value.kitchenId());
        access.authorizeLocation(value.locationId());
        return value;
    }

    public record GenerateRequest(
            @NotBlank String kitchenId,
            @NotBlank String locationId,
            @NotNull Instant horizonStart,
            @NotNull Instant horizonEnd,
            @NotNull Instant expiresAt
    ) {
    }

    public record DecisionRequest(
            @Positive int expectedVersion,
            @NotBlank String reason
    ) {
    }

    public record EditRequest(
            @Positive int expectedVersion,
            @NotNull @DecimalMin(value = "0.0", inclusive = false)
            BigDecimal proposedQuantity,
            @NotBlank String reason
    ) {
    }

    public record ApplyRequest(
            @Positive int expectedVersion,
            @NotBlank String reason
    ) {
    }

    public record OutcomeRequest(
            @Positive int expectedVersion,
            @NotNull OutcomeType type,
            BigDecimal quantity,
            String unit,
            @NotBlank String sourceReferenceType,
            @NotBlank String sourceReferenceId,
            String notes,
            @NotNull Instant occurredAt
    ) {
    }
}
