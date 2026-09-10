package com.bizlama.api.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.google.api.core.ApiFuture;
import com.google.api.core.SettableApiFuture;
import com.google.pubsub.v1.PubsubMessage;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class PubSubOutboxPublisherTest {

    @Test
    void publishesCanonicalCompleteEnvelopeWithStableIdentityAndOrdering() throws Exception {
        CapturingPublisher transport = new CapturingPublisher();
        transport.acknowledgement.set("message-1");
        PubSubOutboxPublisher publisher = publisher(
                transport,
                Duration.ofSeconds(1)
        );
        OutboxEvent event = event();

        publisher.publish(event);

        PubsubMessage message = transport.message;
        JsonNode envelope = mapper().readTree(
                message.getData().toStringUtf8());
        assertThat(envelope.path("eventId").asText()).isEqualTo("event-1");
        assertThat(envelope.path("payloadJson").asText())
                .isEqualTo("{\"quantity\":\"2.500\"}");
        assertThat(envelope.path("sourceMetadataJson").asText())
                .isEqualTo("{\"component\":\"test\"}");
        assertThat(envelope.path("state").asText()).isEqualTo("IN_FLIGHT");
        assertThat(envelope.path("attemptCount").asInt()).isEqualTo(2);
        assertThat(message.getOrderingKey())
                .isEqualTo("kitchen-1/ingredient/rice");
        assertThat(message.getAttributesMap())
                .containsEntry("eventId", "event-1")
                .containsEntry("deduplicationKey", "dedup-1")
                .containsEntry("schemaVersion", "1")
                .containsEntry("occurredAt", "2026-09-09T10:00:00Z")
                .containsEntry("contentType", "application/json");
        assertThat(transport.requestedTimeout)
                .isEqualTo(Duration.ofSeconds(1));
    }

    @Test
    void timeoutIsFailureAndResumesOrderedPublishingForOutboxRetry() {
        CapturingPublisher transport = new CapturingPublisher();
        PubSubOutboxPublisher publisher = publisher(
                transport,
                Duration.ofMillis(5)
        );

        assertThatThrownBy(() -> publisher.publish(event()))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("timed out");
        assertThat(transport.acknowledgement.isCancelled()).isTrue();
        assertThat(transport.resumedOrderingKey)
                .isEqualTo("kitchen-1/ingredient/rice");
    }

    @Test
    void closeUsesBoundedPublisherShutdown() {
        CapturingPublisher transport = new CapturingPublisher();
        PubSubOutboxPublisher publisher = publisher(
                transport,
                Duration.ofSeconds(1)
        );

        publisher.close();

        assertThat(transport.shutdown).isTrue();
        assertThat(transport.shutdownTimeout)
                .isEqualTo(Duration.ofMillis(25));
    }

    private PubSubOutboxPublisher publisher(
            CapturingPublisher transport,
            Duration publishTimeout
    ) {
        PubSubOutboxProperties properties = new PubSubOutboxProperties(
                "project",
                "topic",
                publishTimeout,
                Duration.ofMillis(25)
        );
        return new PubSubOutboxPublisher(
                new CanonicalJson(mapper()),
                properties,
                transport
        );
    }

    private JsonMapper mapper() {
        return JsonMapper.builder()
                .addModule(new JavaTimeModule())
                .build();
    }

    private OutboxEvent event() {
        return new OutboxEvent(
                "event-1",
                "INVENTORY_PURCHASED",
                1,
                "kitchen-1",
                "ingredient",
                "rice",
                Instant.parse("2026-09-09T10:00:00Z"),
                Instant.parse("2026-09-09T10:00:01Z"),
                "correlation-1",
                "cause-1",
                "dedup-1",
                "{\"quantity\":\"2.500\"}",
                "{\"component\":\"test\"}",
                OutboxState.IN_FLIGHT,
                2,
                Instant.parse("2026-09-09T10:00:05Z"),
                null,
                null
        );
    }

    private static final class CapturingPublisher
            implements PubSubOutboxPublisher.PublisherAdapter {

        private final SettableApiFuture<String> acknowledgement =
                SettableApiFuture.create();
        private PubsubMessage message;
        private Duration requestedTimeout;
        private String resumedOrderingKey;
        private boolean shutdown;
        private Duration shutdownTimeout;

        @Override
        public ApiFuture<String> publish(PubsubMessage value) {
            this.message = value;
            return new TrackingFuture(acknowledgement, this);
        }

        @Override
        public void resumePublish(String orderingKey) {
            this.resumedOrderingKey = orderingKey;
        }

        @Override
        public void shutdown() {
            shutdown = true;
        }

        @Override
        public boolean awaitTermination(long timeout, TimeUnit unit) {
            shutdownTimeout = Duration.ofNanos(unit.toNanos(timeout));
            return true;
        }
    }

    private record TrackingFuture(
            SettableApiFuture<String> delegate,
            CapturingPublisher owner
    ) implements ApiFuture<String> {

        @Override
        public void addListener(Runnable listener, java.util.concurrent.Executor executor) {
            delegate.addListener(listener, executor);
        }

        @Override
        public boolean cancel(boolean mayInterruptIfRunning) {
            return delegate.cancel(mayInterruptIfRunning);
        }

        @Override
        public boolean isCancelled() {
            return delegate.isCancelled();
        }

        @Override
        public boolean isDone() {
            return delegate.isDone();
        }

        @Override
        public String get() throws java.util.concurrent.ExecutionException,
                InterruptedException {
            return delegate.get();
        }

        @Override
        public String get(long timeout, TimeUnit unit)
                throws java.util.concurrent.ExecutionException,
                InterruptedException,
                java.util.concurrent.TimeoutException {
            owner.requestedTimeout = Duration.ofNanos(unit.toNanos(timeout));
            return delegate.get(timeout, unit);
        }
    }
}
