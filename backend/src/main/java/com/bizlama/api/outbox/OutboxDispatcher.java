package com.bizlama.api.outbox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Leases in a short database transaction, publishes outside that transaction,
 * then records the outcome in another short transaction. Delivery is at least
 * once; publishers/consumers must deduplicate on the stable event id.
 */
@Component
@ConditionalOnProperty(
        prefix = "bizlama.outbox",
        name = "dispatch-enabled",
        havingValue = "true")
public class OutboxDispatcher {

    private static final Logger log =
            LoggerFactory.getLogger(OutboxDispatcher.class);

    private final TransactionalOutboxService outbox;
    private final OutboxPublisher publisher;
    private final OperationalCutoverBaselineService cutoverBaseline;

    public OutboxDispatcher(
            TransactionalOutboxService outbox,
            OutboxPublisher publisher,
            OperationalCutoverBaselineService cutoverBaseline) {

        this.outbox = outbox;
        this.publisher = publisher;
        this.cutoverBaseline = cutoverBaseline;
    }

    @Scheduled(fixedDelayString = "${bizlama.outbox.poll-interval:PT5S}")
    public void scheduledDispatch() {
        dispatchOnce();
    }

    public int dispatchOnce() {
        cutoverBaseline.drainBatch();
        int completed = 0;
        for (OutboxEvent event : outbox.claimBatch()) {
            try {
                publisher.publish(event);
                if (outbox.markPublished(event.eventId())) {
                    completed++;
                } else {
                    log.warn(
                            "Outbox acknowledgement lost its lease for event {}",
                            event.eventId());
                }
            } catch (Exception failure) {
                boolean recorded = outbox.recordFailure(
                        event.eventId(), failure);
                if (!recorded) {
                    log.warn(
                            "Outbox failure lost its lease for event {}",
                            event.eventId(),
                            failure);
                } else {
                    log.warn(
                            "Outbox publish failed for event {} attempt {}",
                            event.eventId(),
                            event.attemptCount(),
                            failure);
                }
            }
        }
        return completed;
    }
}
