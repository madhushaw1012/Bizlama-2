package com.bizlama.api.signals;

import com.bizlama.api.signals.SignalProposalService.IngestResult;
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

/** Pulls only governed proposal commands using the proposal-worker identity. */
@Component
@ConditionalOnProperty(
        prefix = "bizlama.signals.proposals",
        name = "enabled",
        havingValue = "true")
public class SignalProposalSubscriberWorker implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(
            SignalProposalSubscriberWorker.class);

    private final SignalProposalProperties properties;
    private final SignalProposalProcessor processor;
    private final AtomicBoolean running = new AtomicBoolean();
    private Subscriber subscriber;

    public SignalProposalSubscriberWorker(
            SignalProposalProperties properties,
            SignalProposalProcessor processor
    ) {
        this.properties = properties;
        this.processor = processor;
    }

    @Override
    public synchronized void start() {
        if (running.get()) {
            return;
        }
        properties.requiredExpectedTargetQueue();
        ProjectSubscriptionName subscription = ProjectSubscriptionName.of(
                properties.requiredProjectId(),
                properties.requiredSubscriptionId());
        MessageReceiver receiver = this::receive;
        subscriber = Subscriber.newBuilder(subscription, receiver).build();
        subscriber.startAsync().awaitRunning();
        running.set(true);
        log.info("Governed signal proposal subscriber started subscription={}",
                subscription);
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
        return Integer.MAX_VALUE - 90;
    }

    void receive(PubsubMessage message, AckReplyConsumer consumer) {
        try {
            IngestResult result = processor.process(
                    message.getData().toByteArray(),
                    message.getMessageId(),
                    message.hasPublishTime()
                            ? instant(message.getPublishTime())
                            : null,
                    message.getAttributesMap(),
                    null);
            if (result.outcome() == SignalProposalService.IngestOutcome.REJECTED) {
                log.warn(
                        "Governed signal proposal rejected messageId={} "
                                + "proposalId={} reason={}",
                        message.getMessageId(),
                        result.proposalId(),
                        result.rejectionCode());
            } else {
                log.info(
                        "Governed signal proposal stored messageId={} "
                                + "proposalId={} outcome={}",
                        message.getMessageId(),
                        result.proposalId(),
                        result.outcome());
            }
            consumer.ack();
        } catch (RuntimeException error) {
            log.error(
                    "Governed signal proposal storage failed messageId={}; "
                            + "requesting redelivery",
                    message.getMessageId(),
                    error);
            consumer.nack();
        }
    }

    private static Instant instant(Timestamp timestamp) {
        return Instant.ofEpochSecond(
                timestamp.getSeconds(), timestamp.getNanos());
    }
}
