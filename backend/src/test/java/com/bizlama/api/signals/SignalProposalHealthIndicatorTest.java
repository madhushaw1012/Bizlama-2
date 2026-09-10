package com.bizlama.api.signals;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.actuate.health.Status;

class SignalProposalHealthIndicatorTest {

    @Test
    void disabledConsumerIsHealthyWithoutWorkerLookup() {
        ObjectProvider<SignalProposalSubscriberWorker> workers = provider();
        SignalProposalHealthIndicator indicator = new SignalProposalHealthIndicator(
                properties(false), workers);

        assertThat(indicator.health().getStatus()).isEqualTo(Status.UP);
        verifyNoInteractions(workers);
    }

    @Test
    void enabledConsumerRequiresRunningSubscriber() {
        ObjectProvider<SignalProposalSubscriberWorker> workers = provider();
        SignalProposalSubscriberWorker worker =
                mock(SignalProposalSubscriberWorker.class);
        when(workers.getIfAvailable()).thenReturn(worker);
        SignalProposalHealthIndicator indicator = new SignalProposalHealthIndicator(
                properties(true), workers);

        assertThat(indicator.health().getStatus()).isEqualTo(Status.DOWN);

        when(worker.isRunning()).thenReturn(true);
        assertThat(indicator.health().getStatus()).isEqualTo(Status.UP);
    }

    private static SignalProposalProperties properties(boolean enabled) {
        return new SignalProposalProperties(
                enabled,
                "bizlm-prod",
                "bizlama-governed-proposals-worker",
                "projects/bizlm-prod/topics/bizlama-governed-proposals",
                65_536);
    }

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> provider() {
        return mock(ObjectProvider.class);
    }
}
