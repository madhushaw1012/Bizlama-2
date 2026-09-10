package com.bizlama.api.recipes;

import com.bizlama.api.recipes.LegacyProvenanceReviewService.ConfirmationResult;
import com.bizlama.api.recipes.LegacyProvenanceReviewService.ReviewQueue;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Readable review queue plus owner-only confirmation commands. */
@RestController
@RequestMapping("/api/recipes/provenance")
public class LegacyProvenanceController {

    private final LegacyProvenanceReviewService reviews;

    public LegacyProvenanceController(LegacyProvenanceReviewService reviews) {
        this.reviews = reviews;
    }

    @GetMapping("/reviews")
    public ReviewQueue reviews() {
        return reviews.reviewQueue();
    }

    @PostMapping("/recipe-yields/{recipeVersionId}/confirm")
    public ConfirmationResult confirmYield(
            @PathVariable String recipeVersionId,
            @Valid @RequestBody ConfirmYieldRequest request,
            @AuthenticationPrincipal Jwt jwt
    ) {
        return reviews.confirmLegacyYield(
                recipeVersionId,
                request.yieldQuantity(),
                request.yieldUnit(),
                request.reason(),
                actor(jwt)
        );
    }

    @PostMapping("/orders/{orderId}/lines/{lineNumber}/confirm")
    public ConfirmationResult confirmOrderRecipePin(
            @PathVariable String orderId,
            @PathVariable int lineNumber,
            @Valid @RequestBody ConfirmOrderPinRequest request,
            @AuthenticationPrincipal Jwt jwt
    ) {
        return reviews.confirmOrderRecipePin(
                orderId,
                lineNumber,
                request.recipeVersionId(),
                request.reason(),
                actor(jwt)
        );
    }

    private String actor(Jwt jwt) {
        if (jwt == null) {
            return "local-owner";
        }
        String email = jwt.getClaimAsString("email");
        return email == null || email.isBlank()
                ? jwt.getSubject()
                : email;
    }

    public record ConfirmYieldRequest(
            @NotNull @Positive BigDecimal yieldQuantity,
            @NotBlank String yieldUnit,
            @NotBlank String reason
    ) {
    }

    public record ConfirmOrderPinRequest(
            @NotBlank String recipeVersionId,
            @NotBlank String reason
    ) {
    }
}
