package com.bizlama.api.outbox;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Read-only operational telemetry for the transactional outbox.
 *
 * <p>The scheduled snapshot is deliberately a database query rather than an
 * in-memory counter: process restarts and multiple dispatcher instances do not
 * hide durable backlog or dead-letter rows. Numeric structured-log fields are
 * also suitable for low-cardinality Cloud Logging metrics.</p>
 */
@Component("outbox")
public class OutboxObservability implements HealthIndicator, MeterBinder {

    private static final Logger log = LoggerFactory.getLogger(
            OutboxObservability.class);

    private final JdbcClient jdbc;
    private final Clock clock;
    private final OutboxProperties properties;
    private final Duration warningAge;
    private final AtomicReference<Snapshot> latest =
            new AtomicReference<>(Snapshot.empty());

    public OutboxObservability(
            JdbcClient jdbc,
            Clock clock,
            OutboxProperties properties,
            @Value("${bizlama.observability.outbox.warning-age:PT5M}")
            Duration warningAge
    ) {
        if (warningAge == null || warningAge.isNegative()
                || warningAge.isZero()) {
            throw new IllegalArgumentException(
                    "bizlama.observability.outbox.warning-age must be positive");
        }
        this.jdbc = jdbc;
        this.clock = clock;
        this.properties = properties;
        this.warningAge = warningAge;
    }

    @Scheduled(
            fixedDelayString =
                    "${bizlama.observability.outbox.sample-interval:PT1M}")
    public void sample() {
        Snapshot snapshot = refresh();
        if (!properties.dispatchEnabled()) {
            return;
        }
        log.atInfo()
                .addKeyValue("event", "outbox_telemetry")
                .addKeyValue("pendingEvents", snapshot.pendingEvents())
                .addKeyValue(
                        "oldestPendingSeconds",
                        snapshot.oldestPendingSeconds())
                .addKeyValue(
                        "deadLetterEvents",
                        snapshot.deadLetterEvents())
                .addKeyValue(
                        "rowsWithLastError",
                        snapshot.rowsWithLastError())
                .log("Outbox telemetry snapshot");
    }

    @Override
    public Health health() {
        try {
            Snapshot snapshot = refresh();
            boolean delayed = properties.dispatchEnabled()
                    && snapshot.oldestPendingSeconds()
                    > warningAge.toSeconds();
            return Health.up()
                    .withDetail("dispatchEnabled", properties.dispatchEnabled())
                    .withDetail("pendingEvents", snapshot.pendingEvents())
                    .withDetail(
                            "oldestPendingSeconds",
                            snapshot.oldestPendingSeconds())
                    .withDetail(
                            "deadLetterEvents",
                            snapshot.deadLetterEvents())
                    .withDetail("backlogDelayed", delayed)
                    .build();
        } catch (RuntimeException failure) {
            return Health.down(failure).build();
        }
    }

    @Override
    public void bindTo(MeterRegistry registry) {
        Gauge.builder(
                        "bizlama.outbox.pending.events",
                        latest,
                        value -> value.get().pendingEvents())
                .description("Durable outbox rows waiting for publication")
                .register(registry);
        Gauge.builder(
                        "bizlama.outbox.oldest.pending.seconds",
                        latest,
                        value -> value.get().oldestPendingSeconds())
                .baseUnit("seconds")
                .description("Age of the oldest publishable outbox row")
                .register(registry);
        Gauge.builder(
                        "bizlama.outbox.dead.letter.events",
                        latest,
                        value -> value.get().deadLetterEvents())
                .description("Durable outbox rows that exhausted retries")
                .register(registry);
    }

    Snapshot refresh() {
        Instant observedAt = clock.instant();
        Snapshot snapshot = jdbc.sql("""
                        SELECT
                          COALESCE(SUM(CASE
                            WHEN state IN ('PENDING', 'IN_FLIGHT')
                              AND published_at IS NULL THEN 1 ELSE 0 END), 0)
                            AS pending_events,
                          MIN(CASE
                            WHEN state IN ('PENDING', 'IN_FLIGHT')
                              AND published_at IS NULL
                            THEN COALESCE(recorded_at, occurred_at) END)
                            AS oldest_pending_at,
                          COALESCE(SUM(CASE
                            WHEN state = 'DEAD_LETTER' THEN 1 ELSE 0 END), 0)
                            AS dead_letter_events,
                          COALESCE(SUM(CASE
                            WHEN last_error IS NOT NULL THEN 1 ELSE 0 END), 0)
                            AS rows_with_last_error
                        FROM analytics_outbox
                        """)
                .query((result, rowNumber) -> snapshot(result, observedAt))
                .single();
        latest.set(snapshot);
        return snapshot;
    }

    private static Snapshot snapshot(ResultSet result, Instant observedAt)
            throws SQLException {
        Timestamp oldest = result.getTimestamp("oldest_pending_at");
        long ageSeconds = oldest == null
                ? 0
                : Math.max(
                        0,
                        Duration.between(
                                oldest.toInstant(), observedAt).toSeconds());
        return new Snapshot(
                result.getLong("pending_events"),
                ageSeconds,
                result.getLong("dead_letter_events"),
                result.getLong("rows_with_last_error"),
                observedAt);
    }

    record Snapshot(
            long pendingEvents,
            long oldestPendingSeconds,
            long deadLetterEvents,
            long rowsWithLastError,
            Instant observedAt
    ) {
        static Snapshot empty() {
            return new Snapshot(0, 0, 0, 0, Instant.EPOCH);
        }
    }
}
