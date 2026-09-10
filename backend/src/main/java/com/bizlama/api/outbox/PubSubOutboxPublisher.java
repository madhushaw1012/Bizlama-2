package com.bizlama.api.outbox;

import com.google.api.core.ApiFuture;
import com.google.cloud.pubsub.v1.Publisher;
import com.google.protobuf.ByteString;
import com.google.pubsub.v1.PubsubMessage;
import com.google.pubsub.v1.TopicName;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Publishes the complete immutable outbox envelope to Google Cloud Pub/Sub.
 *
 * <p>A broker acknowledgement is bounded by {@code publish-timeout}. A timeout
 * is deliberately reported as failure even though the broker may eventually
 * accept the request; stable event and deduplication keys make downstream
 * consumers responsible for idempotency.</p>
 */
@Component
@ConditionalOnProperty(
        prefix = "bizlama.outbox",
        name = "publisher",
        havingValue = "pubsub")
public final class PubSubOutboxPublisher implements OutboxPublisher {

    static final String CONTENT_TYPE = "application/json";

    private final CanonicalJson canonicalJson;
    private final PubSubOutboxProperties properties;
    private final PublisherAdapter publisher;

    public PubSubOutboxPublisher(
            CanonicalJson canonicalJson,
            PubSubOutboxProperties properties
    ) throws IOException {
        this(
                canonicalJson,
                properties,
                new GooglePublisherAdapter(buildPublisher(properties))
        );
    }

    PubSubOutboxPublisher(
            CanonicalJson canonicalJson,
            PubSubOutboxProperties properties,
            PublisherAdapter publisher
    ) {
        this.canonicalJson = Objects.requireNonNull(canonicalJson, "canonicalJson");
        this.properties = Objects.requireNonNull(properties, "properties");
        this.publisher = Objects.requireNonNull(publisher, "publisher");
    }

    @Override
    public void publish(OutboxEvent event) throws Exception {
        Objects.requireNonNull(event, "event");
        requireText(event.eventId(), "eventId");
        requireText(event.eventType(), "eventType");
        requireText(event.kitchenId(), "kitchenId");
        requireText(event.entityType(), "entityType");
        requireText(event.entityId(), "entityId");
        requireText(event.deduplicationKey(), "deduplicationKey");
        Objects.requireNonNull(event.occurredAt(), "occurredAt");
        if (event.schemaVersion() < 1) {
            throw new IllegalArgumentException("schemaVersion must be positive");
        }

        String orderingKey = orderingKey(event);
        PubsubMessage message = PubsubMessage.newBuilder()
                .setData(ByteString.copyFromUtf8(canonicalJson.write(event)))
                .setOrderingKey(orderingKey)
                .putAttributes("contentType", CONTENT_TYPE)
                .putAttributes("eventId", event.eventId())
                .putAttributes("deduplicationKey", event.deduplicationKey())
                .putAttributes("eventType", event.eventType())
                .putAttributes(
                        "schemaVersion",
                        Integer.toString(event.schemaVersion()))
                .putAttributes("kitchenId", event.kitchenId())
                .putAttributes("entityType", event.entityType())
                .putAttributes("entityId", event.entityId())
                .putAttributes("occurredAt", event.occurredAt().toString())
                .build();

        ApiFuture<String> acknowledgement = publisher.publish(message);
        try {
            acknowledgement.get(
                    properties.publishTimeout().toMillis(),
                    TimeUnit.MILLISECONDS
            );
        } catch (TimeoutException timeout) {
            acknowledgement.cancel(false);
            publisher.resumePublish(orderingKey);
            throw new IOException(
                    "Pub/Sub publish acknowledgement timed out for event "
                            + event.eventId(),
                    timeout
            );
        } catch (ExecutionException failure) {
            publisher.resumePublish(orderingKey);
            Throwable cause = failure.getCause();
            if (cause instanceof Exception exception) {
                throw exception;
            }
            throw new IOException(
                    "Pub/Sub publish failed for event " + event.eventId(),
                    cause
            );
        } catch (InterruptedException interrupted) {
            publisher.resumePublish(orderingKey);
            Thread.currentThread().interrupt();
            throw interrupted;
        }
    }

    @PreDestroy
    public void close() {
        publisher.shutdown();
        try {
            publisher.awaitTermination(
                    properties.shutdownTimeout().toMillis(),
                    TimeUnit.MILLISECONDS
            );
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    static String orderingKey(OutboxEvent event) {
        return event.kitchenId()
                + "/"
                + event.entityType()
                + "/"
                + event.entityId();
    }

    private static Publisher buildPublisher(
            PubSubOutboxProperties properties
    ) throws IOException {
        String projectId = requireText(properties.projectId(), "project-id");
        String topicId = requireText(properties.topicId(), "topic-id");
        return Publisher.newBuilder(TopicName.of(projectId, topicId))
                .setEnableMessageOrdering(true)
                .build();
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(
                    "Pub/Sub outbox " + name + " is required"
            );
        }
        return value.trim();
    }

    interface PublisherAdapter {
        ApiFuture<String> publish(PubsubMessage message);

        void resumePublish(String orderingKey);

        void shutdown();

        boolean awaitTermination(long timeout, TimeUnit unit)
                throws InterruptedException;
    }

    private record GooglePublisherAdapter(Publisher delegate)
            implements PublisherAdapter {

        @Override
        public ApiFuture<String> publish(PubsubMessage message) {
            return delegate.publish(message);
        }

        @Override
        public void resumePublish(String orderingKey) {
            delegate.resumePublish(orderingKey);
        }

        @Override
        public void shutdown() {
            delegate.shutdown();
        }

        @Override
        public boolean awaitTermination(long timeout, TimeUnit unit)
                throws InterruptedException {
            return delegate.awaitTermination(timeout, unit);
        }
    }
}
