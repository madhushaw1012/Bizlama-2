package com.bizlama.api.experiment;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/experiments")
public class ExperimentController {

    private final ExperimentService experimentService;

    public ExperimentController(ExperimentService experimentService) {
        this.experimentService = experimentService;
    }

    @GetMapping("/{reference}")
    public ExperimentResponse getExperiment(
            @PathVariable String reference) {
        return experimentService.getExperiment(reference);
    }

    @PostMapping("/{reference}/approve")
    public ExperimentResponse approve(
            @PathVariable String reference,
            @AuthenticationPrincipal Jwt jwt) {
        return experimentService.approve(reference, actor(jwt));
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
}
