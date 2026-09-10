package com.bizlama.api.analytics.raw;

import com.google.cloud.pubsub.v1.AckReplyConsumer;
import com.google.cloud.pubsub.v1.MessageReceiver;
import com.google.cloud.pubsub.v1.Subscriber;
import com.google.protobuf.Timestamp;
import com.google.pubsub.v1.ProjectSubscriptionName;
import com.google.pubsub.v1.PubsubMessage;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

/**
 * Separately enabled Pub/Sub pull worker. Processing executes on subscriber
 * threads and never joins an operational JDBC transaction.
 */
@Component
@ConditionalOnProperty(
        prefix = "bizlama.analytics.raw",
        name = "enabled",
        havingValue = "true")
public class RawPubSubSubscriberWorker implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(
            RawPubSubSubscriberWorker.class);

    private final RawAnalyticsProperties properties;
    private final RawEventProcessor processor;
    private final AtomicBoolean running = new AtomicBoolean();
    private Subscriber subscriber;

    public RawPubSubSubscriberWorker(
            RawAnalyticsProperties properties,
            RawEventProcessor processor
    ) {
        this.properties = properties;
        this.processor = processor;
    }

    @Override
    public synchronized void start() {
        if (running.get()) {
            return;
        }
        ProjectSubscriptionName subscription = ProjectSubscriptionName.of(
                properties.requiredProjectId(),
                properties.requiredSubscriptionId());
        MessageReceiver receiver = this::receive;
        subscriber = Subscriber.newBuilder(subscription, receiver).build();
        subscriber.startAsync().awaitRunning();
        running.set(true);
        log.info("Raw analytics subscriber started for {}", subscription);
    }

    @Override
    public synchronized void stop() {
        Subscriber current = subscriber;
        if (current != null) {
            current.stopAsync().awaitTerminated();
            subscriber = null;
        }
        running.set(false);
    }

    @Override
    public void stop(Runnable callback) {
        stop();
        callback.run();
    }

    @Override
    public boolean isRunning() {
        return running.get();
    }

    @Override
    public boolean isAutoStartup() {
        return true;
    }

    @Override
    public int getPhase() {
        return Integer.MAX_VALUE - 100;
    }

    void receive(
            PubsubMessage message,
            AckReplyConsumer consumer
    ) {
        try {
            if (!message.hasPublishTime()) {
                throw new RawEventValidationException(
                        "Pub/Sub publishTime must be present");
            }
            processor.process(
                    message.getData().toByteArray(),
                    message.getMessageId(),
                    instant(message.getPublishTime()),
                    properties.requiredSubscriptionId(),
                    message.getAttributesMap(),
                    null,
                    false);
            consumer.ack();
        } catch (RuntimeException error) {
            log.error(
                    "Raw analytics message {} failed validation or storage; "
                            + "requesting redelivery",
                    message.getMessageId(),
                    error);
            consumer.nack();
        }
    }

    private static Instant instant(Timestamp timestamp) {
        return Instant.ofEpochSecond(
                timestamp.getSeconds(),
                timestamp.getNanos());
    }
}
