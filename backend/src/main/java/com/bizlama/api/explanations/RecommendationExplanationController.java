package com.bizlama.api.explanations;

import com.bizlama.api.recommendations.GovernedRecommendation;
import com.bizlama.api.recommendations.KitchenAccessService;
import com.bizlama.api.recommendations.RecommendationService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/recommendations")
public class RecommendationExplanationController {

    private final RecommendationService recommendations;
    private final RecommendationExplanationService explanations;
    private final KitchenAccessService access;

    public RecommendationExplanationController(
            RecommendationService recommendations,
            RecommendationExplanationService explanations,
            KitchenAccessService access
    ) {
        this.recommendations = recommendations;
        this.explanations = explanations;
        this.access = access;
    }

    @GetMapping("/{id}/explanation")
    public RecommendationExplanation explanation(
            @PathVariable String id,
            @AuthenticationPrincipal Jwt jwt
    ) {
        GovernedRecommendation recommendation =
                recommendations.recommendation(id);
        access.authorizeKitchen(jwt, recommendation.kitchenId());
        return explanations.explain(recommendation);
    }
}
