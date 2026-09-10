package com.bizlama.api.signals;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bizlama.api.signals.SignalProposalService.IngestOutcome;
import com.bizlama.api.signals.SignalProposalService.IngestResult;
import com.google.cloud.pubsub.v1.AckReplyConsumer;
import com.google.protobuf.ByteString;
import com.google.protobuf.Timestamp;
import com.google.pubsub.v1.PubsubMessage;
import org.junit.jupiter.api.Test;

class SignalProposalSubscriberWorkerTest {

    @Test
    void acknowledgesPermanentRejectionsAfterTheyAreAudited() {
        SignalProposalProcessor processor = mock(SignalProposalProcessor.class);
        when(processor.process(any(), eq("message-1"), any(), any(), isNull()))
                .thenReturn(new IngestResult(
                        IngestOutcome.REJECTED,
                        "INVALID_SCHEMA",
                        null));
        AckReplyConsumer consumer = mock(AckReplyConsumer.class);

        worker(processor).receive(message(), consumer);

        verify(consumer).ack();
        verify(consumer, never()).nack();
    }

    @Test
    void nacksTransientStorageFailures() {
        SignalProposalProcessor processor = mock(SignalProposalProcessor.class);
        doThrow(new IllegalStateException("database unavailable"))
                .when(processor)
                .process(any(), eq("message-1"), any(), any(), isNull());
        AckReplyConsumer consumer = mock(AckReplyConsumer.class);

        worker(processor).receive(message(), consumer);

        verify(consumer).nack();
        verify(consumer, never()).ack();
    }

    private static SignalProposalSubscriberWorker worker(
            SignalProposalProcessor processor
    ) {
        return new SignalProposalSubscriberWorker(
                new SignalProposalProperties(
                        true,
                        SignalProposalFixtures.PROJECT,
                        SignalProposalFixtures.SUBSCRIPTION,
                        SignalProposalFixtures.TARGET,
                        65_536),
                processor);
    }

    private static PubsubMessage message() {
        return PubsubMessage.newBuilder()
                .setMessageId("message-1")
                .setData(ByteString.copyFromUtf8("{}"))
                .setPublishTime(Timestamp.newBuilder()
                        .setSeconds(1_788_959_660L)
                        .build())
                .build();
    }
}
