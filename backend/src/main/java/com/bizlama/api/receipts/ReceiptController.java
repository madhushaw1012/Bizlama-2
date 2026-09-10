package com.bizlama.api.receipts;

import com.bizlama.api.stock.ExpiryProvenance;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/receipts")
public class ReceiptController {

    private final ReceiptQueryService queries;
    private final ReceiptWorkflowService workflow;
    private final ReceiptReviewService reviews;

    public ReceiptController(
            ReceiptQueryService queries,
            ReceiptWorkflowService workflow,
            ReceiptReviewService reviews
    ) {
        this.queries = queries;
        this.workflow = workflow;
        this.reviews = reviews;
    }

    @GetMapping
    public List<ReceiptView> list() {
        return queries.list();
    }

    @GetMapping("/{id}")
    public ReceiptView get(@PathVariable String id) {
        return queries.require(id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ReceiptView upload(@RequestParam("file") MultipartFile file) {
        return workflow.upload(file);
    }

    @PostMapping("/{id}/retry")
    public ReceiptView retry(
            @PathVariable String id,
            @Valid @RequestBody RetryReceiptRequest request
    ) {
        return workflow.retryExtraction(
                id,
                request.expectedVersion()
        );
    }

    @PostMapping("/{id}/lines")
    @ResponseStatus(HttpStatus.CREATED)
    public ReceiptView addLine(
            @PathVariable String id,
            @Valid @RequestBody ManualLineRequest request
    ) {
        return workflow.addManualLine(
                id,
                request.expectedVersion(),
                new ReceiptWorkflowService.ManualLine(
                        request.rawName(),
                        request.quantity(),
                        request.unit(),
                        request.unitPrice()
                )
        );
    }

    @PutMapping("/{id}/review")
    public ReceiptView review(
            @PathVariable String id,
            @Valid @RequestBody ReviewReceiptRequest request,
            @AuthenticationPrincipal Jwt jwt
    ) {
        return reviews.review(
                id,
                new ReceiptReviewService.ReviewCommand(
                        request.expectedVersion(),
                        request.purchaseDate(),
                        request.lines().stream()
                                .map(line -> new ReceiptReviewService.LineReview(
                                        line.id(),
                                        line.selected(),
                                        line.ingredientId(),
                                        line.quantity(),
                                        line.unit(),
                                        line.expiresAt(),
                                        line.expiryProvenance()
                                ))
                                .toList()
                ),
                actor(jwt)
        );
    }

    @PostMapping("/{id}/confirm")
    public ReceiptView confirm(
            @PathVariable String id,
            @Valid @RequestBody ConfirmReceiptRequest request,
            @AuthenticationPrincipal Jwt jwt
    ) {
        return reviews.confirm(
                id,
                new ReceiptReviewService.ConfirmCommand(
                        request.expectedVersion(),
                        request.idempotencyKey()
                ),
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

    public record RetryReceiptRequest(
            @Min(1) int expectedVersion
    ) {
    }

    public record ManualLineRequest(
            @Min(1) int expectedVersion,
            @NotBlank String rawName,
            @NotNull @Positive BigDecimal quantity,
            @NotBlank String unit,
            BigDecimal unitPrice
    ) {
    }

    public record ReviewReceiptRequest(
            @Min(1) int expectedVersion,
            @NotNull LocalDate purchaseDate,
            @NotNull List<@Valid ReviewLineRequest> lines
    ) {
    }

    public record ReviewLineRequest(
            @NotBlank String id,
            boolean selected,
            String ingredientId,
            BigDecimal quantity,
            String unit,
            LocalDate expiresAt,
            ExpiryProvenance expiryProvenance
    ) {
    }

    public record ConfirmReceiptRequest(
            @Min(1) int expectedVersion,
            @NotBlank @Size(max = 200) String idempotencyKey
    ) {
    }
}