package com.bizlama.api.signals;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

/** Readiness contribution for the separately enabled proposal subscriber. */
@Component("signalProposals")
public class SignalProposalHealthIndicator implements HealthIndicator {

    private final SignalProposalProperties properties;
    private final ObjectProvider<SignalProposalSubscriberWorker> workers;

    public SignalProposalHealthIndicator(
            SignalProposalProperties properties,
            ObjectProvider<SignalProposalSubscriberWorker> workers
    ) {
        this.properties = properties;
        this.workers = workers;
    }

    @Override
    public Health health() {
        if (!properties.enabled()) {
            return Health.up().withDetail("enabled", false).build();
        }
        SignalProposalSubscriberWorker worker = workers.getIfAvailable();
        if (worker == null) {
            return Health.down()
                    .withDetail("enabled", true)
                    .withDetail("configuration", "subscriber bean missing")
                    .build();
        }
        return worker.isRunning()
                ? Health.up()
                        .withDetail("enabled", true)
                        .withDetail("subscriber", "running")
                        .build()
                : Health.down()
                        .withDetail("enabled", true)
                        .withDetail("subscriber", "not running")
                        .build();
    }
}
