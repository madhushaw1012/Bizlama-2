package com.bizlama.api.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import javax.sql.DataSource;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import com.fasterxml.jackson.databind.ObjectMapper;

class TransactionalOutboxServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-09T12:00:00Z");

    private DataSource dataSource;
    private JdbcClient jdbc;
    private TransactionTemplate transaction;
    private MutableClock clock;
    private TransactionalOutboxService service;

    @BeforeEach
    void setUp() {
        dataSource = dataSource();
        createLegacySchema(dataSource);
        migrate(dataSource);
        jdbc = JdbcClient.create(dataSource);
        transaction = new TransactionTemplate(
                new DataSourceTransactionManager(dataSource));
        clock = new MutableClock(NOW);
        service = service(jdbc, clock);
    }

    @Test
    void migrationBackfillsExistingRowsAndStillAcceptsLegacyInserts() {
        DataSource legacy = dataSource();
        createLegacySchema(legacy);
        JdbcTemplate legacyJdbc = new JdbcTemplate(legacy);
        legacyJdbc.update("""
                INSERT INTO analytics_outbox
                (id, kitchen_id, event_type, aggregate_type, aggregate_id,
                 payload_json, occurred_at, published_at, attempt_count)
                VALUES (?, 'kitchen-default', 'ORDER_CREATED', 'order', ?,
                        '{}', ?, ?, 1)
                """,
                "legacy-published",
                "order-1",
                java.sql.Timestamp.from(NOW.minusSeconds(60)),
                java.sql.Timestamp.from(NOW.minusSeconds(30)));

        migrate(legacy);
        JdbcClient migrated = JdbcClient.create(legacy);

        Map<String, Object> row = migrated.sql("""
                        SELECT event_id, entity_type, entity_id,
                               deduplication_key, state, next_attempt_at
                        FROM analytics_outbox
                        WHERE id = 'legacy-published'
                        """)
                .query()
                .singleRow();
        assertThat(row)
                .containsEntry("event_id", "legacy-published")
                .containsEntry("entity_type", "order")
                .containsEntry("entity_id", "order-1")
                .containsEntry("deduplication_key", "legacy-published")
                .containsEntry("state", "PUBLISHED")
                .containsEntry("next_attempt_at", null);

        migrated.sql("""
                        INSERT INTO analytics_outbox
                        (id, kitchen_id, event_type, aggregate_type,
                         aggregate_id, payload_json, occurred_at)
                        VALUES
                        ('legacy-after-v10', 'kitchen-default', 'ACTIVITY',
                         'activity', 'activity-1', '{}', :occurredAt)
                        """)
                .param("occurredAt", NOW)
                .update();

        OutboxEvent inserted = service(
                migrated, new MutableClock(NOW))
                .find("legacy-after-v10")
                .orElseThrow();
        assertThat(inserted.eventId()).isEqualTo("legacy-after-v10");
        assertThat(inserted.entityType()).isEqualTo("activity");
        assertThat(inserted.entityId()).isEqualTo("activity-1");
        assertThat(inserted.deduplicationKey()).isEqualTo("legacy-after-v10");
        assertThat(inserted.state()).isEqualTo(OutboxState.PENDING);
        assertThat(inserted.nextAttemptAt()).isNotNull();
    }

    @Test
    void appendUsesCallerTransactionCanonicalizesAndDeduplicates() {
        OutboxEventDraft draft = new OutboxEventDraft(
                null,
                "STOCK_ADJUSTED",
                1,
                "kitchen-default",
                "stock_lot",
                "lot-7",
                NOW.minusSeconds(10),
                "correlation-1",
                "cause-1",
                "stock-adjustment:request-9",
                Map.of(
                        "z", List.of(Map.of("b", 2, "a", 1)),
                        "a", true),
                Map.of("request", "request-9", "producer", "stock"));

        OutboxEvent first = inTransaction(() -> service.append(draft));
        OutboxEvent duplicate = inTransaction(() -> service.append(draft));

        assertThat(duplicate.eventId()).isEqualTo(first.eventId());
        assertThat(first.payloadJson()).isEqualTo(
                "{\"a\":true,\"z\":[{\"a\":1,\"b\":2}]}");
        assertThat(first.sourceMetadataJson()).isEqualTo(
                "{\"producer\":\"stock\",\"request\":\"request-9\"}");
        assertThat(first.occurredAt()).isEqualTo(NOW.minusSeconds(10));
        assertThat(first.recordedAt()).isEqualTo(NOW);
        assertThat(first.state()).isEqualTo(OutboxState.PENDING);
        assertThat(jdbc.sql("SELECT COUNT(*) FROM analytics_outbox")
                .query(Long.class)
                .single()).isEqualTo(1L);

        String rolledBackId = transaction.execute(status -> {
            OutboxEvent value = service.append(OutboxEventDraft.v1(
                    "ORDER_CREATED",
                    "kitchen-default",
                    "order",
                    "order-9",
                    "order-created:request-10",
                    Map.of("total", 12)));
            status.setRollbackOnly();
            return value.eventId();
        });
        assertThat(service.find(rolledBackId)).isEmpty();
    }

    @Test
    void claimRetriesWithCapDeadLettersAndReplaysStableEnvelope() {
        OutboxEvent original = inTransaction(() -> service.append(
                OutboxEventDraft.v1(
                        "ORDER_CREATED",
                        "kitchen-default",
                        "order",
                        "order-42",
                        "order-created:42",
                        Map.of("orderId", "order-42"))));

        OutboxEvent first = claimOne();
        assertThat(first.attemptCount()).isEqualTo(1);
        assertThat(first.state()).isEqualTo(OutboxState.IN_FLIGHT);
        assertThat(inTransaction(() -> service.recordFailure(
                first.eventId(), new IllegalStateException("broker down"))))
                .isTrue();
        assertThat(service.find(original.eventId()).orElseThrow())
                .satisfies(event -> {
                    assertThat(event.state()).isEqualTo(OutboxState.PENDING);
                    assertThat(event.nextAttemptAt())
                            .isEqualTo(NOW.plusSeconds(2));
                    assertThat(event.lastError())
                            .isEqualTo("IllegalStateException: broker down");
                });

        assertThat(inTransaction(service::claimBatch)).isEmpty();
        clock.advance(Duration.ofSeconds(2));
        OutboxEvent second = claimOne();
        assertThat(second.attemptCount()).isEqualTo(2);
        assertThat(inTransaction(() -> service.recordFailure(
                second.eventId(), new RuntimeException("still down"))))
                .isTrue();

        clock.advance(Duration.ofSeconds(4));
        OutboxEvent third = claimOne();
        assertThat(third.attemptCount()).isEqualTo(3);
        assertThat(inTransaction(() -> service.recordFailure(
                third.eventId(), new RuntimeException("final failure"))))
                .isTrue();

        OutboxEvent dead = service.find(original.eventId()).orElseThrow();
        assertThat(dead.state()).isEqualTo(OutboxState.DEAD_LETTER);
        assertThat(dead.nextAttemptAt()).isNull();
        assertThat(inTransaction(() -> service.replay(dead.eventId())))
                .isTrue();

        OutboxEvent replayed = service.find(original.eventId()).orElseThrow();
        assertThat(replayed.eventId()).isEqualTo(original.eventId());
        assertThat(replayed.deduplicationKey())
                .isEqualTo(original.deduplicationKey());
        assertThat(replayed.attemptCount()).isZero();
        assertThat(replayed.state()).isEqualTo(OutboxState.PENDING);

        OutboxEvent claimedReplay = claimOne();
        assertThat(inTransaction(() -> service.markPublished(
                claimedReplay.eventId()))).isTrue();
        assertThat(service.find(original.eventId()).orElseThrow())
                .satisfies(event -> {
                    assertThat(event.state()).isEqualTo(OutboxState.PUBLISHED);
                    assertThat(event.publishedAt()).isEqualTo(clock.instant());
                    assertThat(event.nextAttemptAt()).isNull();
                });
    }

    @Test
    void observabilityReportsDurableBacklogAndDeadLetters() {
        OutboxProperties telemetryProperties = new OutboxProperties(
                true,
                "local",
                10,
                3,
                Duration.ofSeconds(2),
                Duration.ofSeconds(5),
                Duration.ofSeconds(30),
                Duration.ofSeconds(5),
                "telemetry-worker");
        OutboxObservability observability = new OutboxObservability(
                jdbc,
                clock,
                telemetryProperties,
                Duration.ofSeconds(60));
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        observability.bindTo(meters);

        inTransaction(() -> service.append(OutboxEventDraft.v1(
                "ORDER_CREATED",
                "kitchen-default",
                "order",
                "order-observed",
                "order-created:observed",
                Map.of("orderId", "order-observed"))));
        clock.advance(Duration.ofSeconds(90));

        OutboxObservability.Snapshot pending = observability.refresh();
        assertThat(pending.pendingEvents()).isEqualTo(1);
        assertThat(pending.oldestPendingSeconds()).isEqualTo(90);
        assertThat(pending.deadLetterEvents()).isZero();
        assertThat(meters.get("bizlama.outbox.pending.events")
                .gauge().value()).isEqualTo(1);

        jdbc.sql("""
                        UPDATE analytics_outbox
                        SET state = 'DEAD_LETTER',
                            next_attempt_at = NULL,
                            last_error = 'terminal test failure'
                        WHERE entity_id = 'order-observed'
                        """)
                .update();

        OutboxObservability.Snapshot deadLetter = observability.refresh();
        assertThat(deadLetter.pendingEvents()).isZero();
        assertThat(deadLetter.deadLetterEvents()).isEqualTo(1);
        assertThat(deadLetter.rowsWithLastError()).isEqualTo(1);
        assertThat(observability.health().getStatus().getCode())
                .isEqualTo("UP");
    }

    @Test
    void appendContractRequiresAnExistingTransaction() throws Exception {
        Transactional annotation = TransactionalOutboxService.class
                .getMethod("append", OutboxEventDraft.class)
                .getAnnotation(Transactional.class);

        assertThat(annotation).isNotNull();
        assertThat(annotation.propagation()).isEqualTo(Propagation.MANDATORY);
    }

    private OutboxEvent claimOne() {
        List<OutboxEvent> events = inTransaction(service::claimBatch);
        assertThat(events).hasSize(1);
        return events.getFirst();
    }

    private <T> T inTransaction(java.util.function.Supplier<T> action) {
        return transaction.execute(status -> action.get());
    }

    private TransactionalOutboxService service(
            JdbcClient client,
            Clock serviceClock) {

        OutboxProperties properties = new OutboxProperties(
                false,
                "local",
                10,
                3,
                Duration.ofSeconds(2),
                Duration.ofSeconds(5),
                Duration.ofSeconds(30),
                Duration.ofSeconds(5),
                "test-worker");
        return new TransactionalOutboxService(
                client,
                new CanonicalJson(new ObjectMapper()),
                serviceClock,
                properties,
                new OutboxRetryPolicy(properties));
    }

    private static DataSource dataSource() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName("org.h2.Driver");
        dataSource.setUrl(
                "jdbc:h2:mem:outbox-" + UUID.randomUUID()
                        + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE"
                        + ";DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1");
        dataSource.setUsername("sa");
        dataSource.setPassword("");
        return dataSource;
    }

    private static void createLegacySchema(DataSource dataSource) {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("""
                CREATE TABLE kitchens (
                    id VARCHAR(80) PRIMARY KEY
                )
                """);
        jdbc.update("INSERT INTO kitchens (id) VALUES (?)", "kitchen-default");
        jdbc.execute("""
                CREATE TABLE analytics_outbox (
                    id VARCHAR(100) PRIMARY KEY,
                    kitchen_id VARCHAR(80) NOT NULL REFERENCES kitchens(id),
                    event_type VARCHAR(80) NOT NULL,
                    aggregate_type VARCHAR(80) NOT NULL,
                    aggregate_id VARCHAR(120) NOT NULL,
                    payload_json TEXT NOT NULL,
                    occurred_at TIMESTAMP WITH TIME ZONE NOT NULL,
                    published_at TIMESTAMP WITH TIME ZONE,
                    attempt_count INTEGER NOT NULL DEFAULT 0,
                    last_error VARCHAR(1000)
                )
                """);
    }

    private static void migrate(DataSource dataSource) {
        new ResourceDatabasePopulator(new ClassPathResource(
                "db/migration/V10__transactional_outbox_foundation.sql"))
                .execute(dataSource);
    }

    private static final class MutableClock extends Clock {

        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        void advance(Duration duration) {
            instant = instant.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return Clock.fixed(instant, zone);
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
