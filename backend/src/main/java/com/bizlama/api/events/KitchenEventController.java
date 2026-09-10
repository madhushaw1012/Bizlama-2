package com.bizlama.api.events;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/events")
public class KitchenEventController {

    private final KitchenEventIntentRouter router;
    private final KitchenEventProposalService proposals;

    public KitchenEventController(
            KitchenEventIntentRouter router,
            KitchenEventProposalService proposals
    ) {
        this.router = router;
        this.proposals = proposals;
    }

    @PostMapping("/parse")
    public ParseKitchenEventResponse parse(
            @Valid @RequestBody ParseKitchenEventRequest request
    ) {
        List<ParsedKitchenEvent> events;
        try {
            events = router.route(request.statement());
        } catch (ResponseStatusException error) {
            return ParseKitchenEventResponse.unknown(
                    error.getReason() == null
                            ? "Clarify the activity and try again."
                            : error.getReason()
            );
        }
        KitchenEventProposalService.Proposal proposal =
                proposals.create(request.statement(), events);

        return new ParseKitchenEventResponse(
                proposal.id(),
                proposal.version(),
                proposal.riskTier(),
                proposal.expiresAt(),
                proposal.events(),
                true,
                false
        );
    }

    @PostMapping("/confirm")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void confirm(
            @Valid @RequestBody ConfirmKitchenEventRequest request,
            @AuthenticationPrincipal Jwt jwt
    ) {
        KitchenEventProposalService.ConfirmationResult result =
                proposals.confirm(
                        request.proposalId(),
                        request.expectedVersion(),
                        request.idempotencyKey(),
                        actor(jwt)
                );
        if (result.expired()) {
            throw new ResponseStatusException(
                    HttpStatus.GONE,
                    "Kitchen event proposal expired; parse the statement again."
            );
        }
    }

    @PostMapping("/{id}/supersede")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void supersede(
            @PathVariable String id,
            @Valid @RequestBody SupersedeRequest request,
            @AuthenticationPrincipal Jwt jwt
    ) {
        proposals.supersede(id, request.expectedVersion(), actor(jwt));
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

    public record SupersedeRequest(@Min(1) int expectedVersion) {
    }
}