package com.bizlama.api.signals;

import com.bizlama.api.recommendations.KitchenAccessService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/signal-proposals")
public class SignalProposalController {

    private final SignalProposalService proposals;
    private final KitchenAccessService access;

    public SignalProposalController(
            SignalProposalService proposals,
            KitchenAccessService access
    ) {
        this.proposals = proposals;
        this.access = access;
    }

    @GetMapping
    public List<SignalProposalView> list(
            @RequestParam String kitchenId,
            @RequestParam String locationId,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "100") int limit,
            @AuthenticationPrincipal Jwt jwt
    ) {
        String authorizedKitchen = access.authorizeKitchen(jwt, kitchenId);
        return proposals.list(
                authorizedKitchen,
                access.authorizeLocation(locationId),
                status == null ? null : status.trim().toUpperCase(),
                limit);
    }

    @GetMapping("/{id}")
    public SignalProposalView get(
            @PathVariable String id,
            @AuthenticationPrincipal Jwt jwt
    ) {
        SignalProposalView result = proposals.get(id);
        access.authorizeKitchen(jwt, result.kitchenId());
        access.authorizeLocation(result.locationId());
        return result;
    }

    @PostMapping("/{id}/dismiss")
    public SignalProposalView dismiss(
            @PathVariable String id,
            @Valid @RequestBody DismissRequest request,
            @AuthenticationPrincipal Jwt jwt
    ) {
        SignalProposalView result = proposals.get(id);
        String kitchen = access.authorizeKitchen(jwt, result.kitchenId());
        access.authorizeLocation(result.locationId());
        return proposals.dismiss(
                result.proposalId(),
                kitchen,
                result.locationId(),
                request.expectedVersion(),
                access.actor(jwt),
                request.reason());
    }

    public record DismissRequest(
            @Positive int expectedVersion,
            @NotBlank @Size(max = 500) String reason
    ) {
    }
}
