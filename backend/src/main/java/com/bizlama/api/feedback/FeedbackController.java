package com.bizlama.api.feedback;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.bizlama.api.domain.Feedback;
import com.bizlama.api.store.OperationalRepository;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

@RestController
@RequestMapping("/api/feedback")
public class FeedbackController {

    private final OperationalRepository store;
    private final FeedbackService feedback;

    public FeedbackController(
            OperationalRepository store,
            FeedbackService feedback
    ) {
        this.store = store;
        this.feedback = feedback;
    }

    @GetMapping
    public List<Feedback> list(
            @RequestParam(required = false) String recipeId) {
        return store.feedback(recipeId);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Feedback create(
            @Valid @RequestBody CreateFeedbackRequest request) {
        return feedback.capture(
                request.recipeId(),
                request.text(),
                request.rating(),
                request.source()
        );
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String id) {
        feedback.delete(id);
    }

    public record CreateFeedbackRequest(
            @NotBlank String recipeId,
            @NotBlank String text,
            @Min(1) @Max(5) int rating,
            @NotBlank String source
    ) {
    }
}