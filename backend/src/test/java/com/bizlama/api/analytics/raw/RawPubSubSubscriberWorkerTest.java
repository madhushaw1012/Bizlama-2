package com.bizlama.api.analytics.raw;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.google.cloud.pubsub.v1.AckReplyConsumer;
import com.google.protobuf.ByteString;
import com.google.protobuf.Timestamp;
import com.google.pubsub.v1.PubsubMessage;
import org.junit.jupiter.api.Test;

class RawPubSubSubscriberWorkerTest {

    @Test
    void acknowledgesOnlyAfterSuccessfulProcessing() {
        RawEventProcessor processor = mock(RawEventProcessor.class);
        AckReplyConsumer consumer = mock(AckReplyConsumer.class);
        RawPubSubSubscriberWorker worker = worker(processor);

        worker.receive(message(), consumer);

        verify(processor).process(
                any(byte[].class),
                eq("message-1"),
                eq(java.time.Instant.parse("2026-09-09T12:04:00Z")),
                eq("bizlama-raw-sub"),
                eq(java.util.Map.of("eventId", "evt-101")),
                isNull(),
                eq(false));
        verify(consumer).ack();
        verify(consumer, never()).nack();
    }

    @Test
    void nacksValidationOrStorageFailures() {
        RawEventProcessor processor = mock(RawEventProcessor.class);
        doThrow(new IllegalStateException("warehouse unavailable"))
                .when(processor)
                .process(
                        any(byte[].class),
                        eq("message-1"),
                        any(),
                        eq("bizlama-raw-sub"),
                        eq(java.util.Map.of("eventId", "evt-101")),
                        isNull(),
                        eq(false));
        AckReplyConsumer consumer = mock(AckReplyConsumer.class);

        worker(processor).receive(message(), consumer);

        verify(consumer).nack();
        verify(consumer, never()).ack();
    }

    private static RawPubSubSubscriberWorker worker(
            RawEventProcessor processor
    ) {
        return new RawPubSubSubscriberWorker(
                new RawAnalyticsProperties(
                        true,
                        "bizlama-project",
                        "bizlama-raw-sub",
                        "bizlama_analytics",
                        "operational_events_raw"),
                processor);
    }

    private static PubsubMessage message() {
        return PubsubMessage.newBuilder()
                .setMessageId("message-1")
                .setData(ByteString.copyFromUtf8("{}"))
                .putAttributes("eventId", "evt-101")
                .setPublishTime(Timestamp.newBuilder()
                        .setSeconds(1788955440L)
                        .build())
                .build();
    }
}
