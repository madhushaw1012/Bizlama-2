package com.bizlama.api.system;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.bizlama.api.analytics.raw.RawAnalyticsHealthIndicator;
import com.bizlama.api.analytics.raw.RawAnalyticsProperties;
import com.bizlama.api.ai.GeminiModelClient.Operation;
import com.bizlama.api.ai.GeminiRuntimeStatus;
import com.bizlama.api.ai.GeminiRuntimeStatus.ValidationResult;
import com.bizlama.api.receipts.ReceiptStorageHealthIndicator;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.simple.JdbcClient;

class SystemStatusControllerTest {

    @Test
    void distinguishesConfiguredReachableDegradedAndDisabledAi() {
        JdbcClient jdbc = mock(JdbcClient.class);
        when(jdbc.sql(anyString())).thenThrow(
                new IllegalStateException("database probe is not under test")
        );
        ReceiptStorageHealthIndicator receiptHealth =
                mock(ReceiptStorageHealthIndicator.class);
        when(receiptHealth.health()).thenReturn(Health.up().build());
        RawAnalyticsProperties analytics =
                mock(RawAnalyticsProperties.class);
        when(analytics.enabled()).thenReturn(false);
        GeminiRuntimeStatus modelStatus = new GeminiRuntimeStatus();

        new ApplicationContextRunner()
                .withPropertyValues(
                        "spring.datasource.url=jdbc:postgresql://db/bizlama",
                        "bizlama.receipts.storage-mode=gcs",
                        "bizlama.receipts.ai.enabled=true",
                        "bizlama.receipts.ai.model=gemini-test",
                        "bizlama.auth.mode=local"
                )
                .withBean(JdbcClient.class, () -> jdbc)
                .withBean(
                        ReceiptStorageHealthIndicator.class,
                        () -> receiptHealth
                )
                .withBean(RawAnalyticsProperties.class, () -> analytics)
                .withBean(
                        RawAnalyticsHealthIndicator.class,
                        () -> mock(RawAnalyticsHealthIndicator.class)
                )
                .withBean(GeminiRuntimeStatus.class, () -> modelStatus)
                .withUserConfiguration(SystemStatusController.class)
                .run(context -> {
                    assertThat(context).hasNotFailed();

                    SystemStatusController controller =
                            context.getBean(SystemStatusController.class);
                    SystemStatusController.Connection receipt = connection(
                            controller,
                            "Receipt recognition"
                    );

                    assertThat(receipt.provider())
                            .isEqualTo("Vertex AI (gemini-test)");
                    assertThat(receipt.state()).isEqualTo("CONFIGURED");
                    assertThat(receipt.connected()).isFalse();
                    assertThat(receipt.detail())
                            .contains("no bounded request");

                    modelStatus.succeeded(
                            Operation.RECEIPT_EXTRACTION,
                            "gemini-test",
                            12
                    );
                    receipt = connection(controller, "Receipt recognition");
                    assertThat(receipt.state()).isEqualTo("REACHABLE");
                    assertThat(receipt.connected()).isTrue();
                    assertThat(receipt.diagnostic().latencyMillis())
                            .isEqualTo(12);

                    modelStatus.failed(
                            Operation.RECEIPT_EXTRACTION,
                            "gemini-test",
                            8,
                            ValidationResult.NOT_RUN,
                            new SecurityException("not exposed")
                    );
                    receipt = connection(controller, "Receipt recognition");
                    assertThat(receipt.state()).isEqualTo("DEGRADED");
                    assertThat(receipt.connected()).isFalse();
                    assertThat(receipt.diagnostic().errorCode())
                            .isEqualTo("PERMISSION_DENIED");

                    SystemStatusController.Connection explanations =
                            connection(
                                    controller,
                                    "Recommendation explanations"
                            );
                    assertThat(explanations.state()).isEqualTo("DISABLED");
                    assertThat(explanations.connected()).isFalse();
                });
    }

    private SystemStatusController.Connection connection(
            SystemStatusController controller,
            String name
    ) {
        return controller.status()
                .connections()
                .stream()
                .filter(connection -> connection.name().equals(name))
                .findFirst()
                .orElseThrow();
    }
}
