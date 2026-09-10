package com.bizlama.api.ai;

import com.bizlama.api.ai.GeminiModelClient.Operation;
import java.time.Instant;
import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Process-local observations for safe status reporting. This component never
 * probes Vertex by itself, so reading status cannot incur cost or mutate data.
 */
@Component
public final class GeminiRuntimeStatus {

    private static final Logger LOGGER =
            LoggerFactory.getLogger(GeminiRuntimeStatus.class);

    private final Map<Operation, Observation> observations =
            new EnumMap<>(Operation.class);

    public synchronized void succeeded(
            Operation operation,
            String model,
            long latencyMillis
    ) {
        Observation observation = new Observation(
                State.REACHABLE,
                operation,
                model,
                Math.max(0, latencyMillis),
                "SUCCESS",
                ValidationResult.PASSED,
                null,
                Instant.now()
        );
        observations.put(operation, observation);
        LOGGER.info(
                "gemini_operation operation={} model={} latencyMs={} outcome={} validation={}",
                operation,
                model,
                observation.latencyMillis(),
                observation.outcome(),
                observation.validationResult()
        );
    }

    public synchronized void failed(
            Operation operation,
            String model,
            long latencyMillis,
            ValidationResult validationResult,
            Throwable failure
    ) {
        String errorCode = classify(failure, validationResult);
        Observation observation = new Observation(
                State.DEGRADED,
                operation,
                model,
                Math.max(0, latencyMillis),
                "ERROR",
                validationResult,
                errorCode,
                Instant.now()
        );
        observations.put(operation, observation);
        LOGGER.warn(
                "gemini_operation operation={} model={} latencyMs={} outcome={} validation={} errorCode={}",
                operation,
                model,
                observation.latencyMillis(),
                observation.outcome(),
                observation.validationResult(),
                errorCode
        );
    }

    public synchronized Optional<Observation> observation(
            Operation operation
    ) {
        return Optional.ofNullable(observations.get(operation));
    }

    public static long elapsedMillis(long startedNanos) {
        return Math.max(
                0,
                java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(
                        System.nanoTime() - startedNanos
                )
        );
    }

    private String classify(
            Throwable failure,
            ValidationResult validationResult
    ) {
        if (validationResult == ValidationResult.FAILED) {
            return "VALIDATION_FAILED";
        }
        Throwable current = failure;
        while (current != null) {
            String type = current.getClass().getSimpleName()
                    .toLowerCase(java.util.Locale.ROOT);
            if (current instanceof TimeoutException
                    || type.contains("timeout")) {
                return "TIMEOUT";
            }
            if (current instanceof SecurityException
                    || type.contains("permission")
                    || type.contains("forbidden")
                    || type.contains("unauthenticated")) {
                return "PERMISSION_DENIED";
            }
            current = current.getCause();
        }
        return "PROVIDER_ERROR";
    }

    public enum State {
        REACHABLE,
        DEGRADED
    }

    public enum ValidationResult {
        PASSED,
        FAILED,
        NOT_RUN
    }

    public record Observation(
            State state,
            Operation operation,
            String model,
            long latencyMillis,
            String outcome,
            ValidationResult validationResult,
            String errorCode,
            Instant observedAt
    ) {
    }
}
