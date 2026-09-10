package com.bizlama.api.system;

import com.bizlama.api.analytics.raw.RawAnalyticsHealthIndicator;
import com.bizlama.api.analytics.raw.RawAnalyticsProperties;
import com.bizlama.api.receipts.ReceiptStorageHealthIndicator;
import com.bizlama.api.ai.GeminiModelClient.Operation;
import com.bizlama.api.ai.GeminiRuntimeStatus;
import com.bizlama.api.ai.GeminiRuntimeStatus.Observation;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.health.Status;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/system")
public class SystemStatusController {

    private final JdbcClient jdbc;
    private final Environment springEnvironment;
    private final String databaseUrl;
    private final String receiptStorage;
    private final boolean receiptAiEnabled;
    private final String receiptAiModel;
    private final boolean explanationAiEnabled;
    private final String explanationAiModel;
    private final String authMode;
    private final ReceiptStorageHealthIndicator receiptStorageHealth;
    private final RawAnalyticsProperties rawAnalyticsProperties;
    private final RawAnalyticsHealthIndicator rawAnalyticsHealth;
    private final GeminiRuntimeStatus geminiStatus;

    public SystemStatusController(
            JdbcClient jdbc,
            Environment springEnvironment,
            @Value("${spring.datasource.url}") String databaseUrl,
            @Value("${bizlama.receipts.storage-mode:local}") String receiptStorage,
            @Value("${bizlama.receipts.ai.enabled:false}")
            boolean receiptAiEnabled,
            @Value("${bizlama.receipts.ai.model:}") String receiptAiModel,
            @Value("${bizlama.explanations.vertex.enabled:false}")
            boolean explanationAiEnabled,
            @Value("${bizlama.explanations.vertex.model:}")
            String explanationAiModel,
            @Value("${bizlama.auth.mode:local}") String authMode,
            ReceiptStorageHealthIndicator receiptStorageHealth,
            RawAnalyticsProperties rawAnalyticsProperties,
            RawAnalyticsHealthIndicator rawAnalyticsHealth,
            GeminiRuntimeStatus geminiStatus
    ) {
        this.jdbc = jdbc;
        this.springEnvironment = springEnvironment;
        this.databaseUrl = databaseUrl;
        this.receiptStorage = receiptStorage;
        this.receiptAiEnabled = receiptAiEnabled;
        this.receiptAiModel = receiptAiModel;
        this.explanationAiEnabled = explanationAiEnabled;
        this.explanationAiModel = explanationAiModel;
        this.authMode = authMode;
        this.receiptStorageHealth = receiptStorageHealth;
        this.rawAnalyticsProperties = rawAnalyticsProperties;
        this.rawAnalyticsHealth = rawAnalyticsHealth;
        this.geminiStatus = geminiStatus;
    }

    @GetMapping("/status")
    public WorkspaceStatus status() {
        boolean cloudProfile = springEnvironment.acceptsProfiles(
                Profiles.of("cloud")
        );
        boolean databaseHealthy = databaseHealthy();
        boolean localFiles = receiptStorage.equalsIgnoreCase("local");
        boolean receiptHealthy = isUp(receiptStorageHealth.health().getStatus());
        boolean rawAnalyticsEnabled = rawAnalyticsProperties.enabled();
        boolean rawAnalyticsHealthy = rawAnalyticsEnabled
                && isUp(rawAnalyticsHealth.health().getStatus());

        return new WorkspaceStatus(
                cloudProfile ? "cloud" : "local",
                List.of(
                        new Connection(
                                "Operational data",
                                databaseUrl.startsWith("jdbc:postgresql")
                                        ? "PostgreSQL"
                                        : "Local development database",
                                databaseHealthy,
                                databaseHealthy
                                        ? "Live query succeeded"
                                        : "Live query failed"
                        ),
                        new Connection(
                                "Receipt files",
                                localFiles
                                        ? "Local private files"
                                        : "Private Cloud Storage",
                                receiptHealthy,
                                receiptHealthy
                                        ? "Selected storage is reachable"
                                        : "Selected storage health check failed"
                        ),
                        new Connection(
                                "Analytics",
                                rawAnalyticsEnabled
                                        ? "Pub/Sub to BigQuery raw retention"
                                        : "Disabled",
                                rawAnalyticsHealthy,
                                rawAnalyticsEnabled
                                        ? (rawAnalyticsHealthy
                                                ? "Subscriber and BigQuery table are reachable"
                                                : "Enabled dependency health check failed")
                                        : "Raw retention worker is disabled in this process"
                        ),
                        aiConnection(
                                "Receipt recognition",
                                receiptAiEnabled,
                                receiptAiModel,
                                Operation.RECEIPT_EXTRACTION,
                                "Manual review mode",
                                "Deterministic manual review is active"
                        ),
                        aiConnection(
                                "Recommendation explanations",
                                explanationAiEnabled,
                                explanationAiModel,
                                Operation.RECOMMENDATION_EXPLANATION,
                                "Deterministic explanations",
                                "Deterministic explanation fallback is active"
                        ),
                        new Connection(
                                "Sign-in",
                                authMode.equalsIgnoreCase("identity-platform")
                                        ? "Identity Platform token verification"
                                        : "Local owner account",
                                true,
                                authMode.equalsIgnoreCase("identity-platform")
                                        ? "JWTs are verified locally against configured issuer keys"
                                        : "Local token service is active"
                        )
                )
        );
    }

    private Connection aiConnection(
            String name,
            boolean enabled,
            String model,
            Operation operation,
            String disabledProvider,
            String disabledDetail
    ) {
        if (!enabled) {
            return new Connection(
                    name,
                    disabledProvider,
                    false,
                    disabledDetail,
                    "DISABLED",
                    null
            );
        }
        Observation observation = geminiStatus.observation(operation)
                .orElse(null);
        if (observation == null) {
            return new Connection(
                    name,
                    "Vertex AI (" + model + ")",
                    false,
                    "Configured; no bounded request has established reachability",
                    "CONFIGURED",
                    null
            );
        }
        boolean reachable = observation.state()
                == GeminiRuntimeStatus.State.REACHABLE;
        return new Connection(
                name,
                "Vertex AI (" + model + ")",
                reachable,
                reachable
                        ? "Last bounded request succeeded"
                        : "Last request failed safely; fallback remains active",
                observation.state().name(),
                observation
        );
    }

    private boolean databaseHealthy() {
        try {
            return jdbc.sql("SELECT 1")
                    .query(Integer.class)
                    .single() == 1;
        } catch (RuntimeException error) {
            return false;
        }
    }

    private static boolean isUp(Status status) {
        return Status.UP.equals(status);
    }

    public record WorkspaceStatus(
            String environment,
            List<Connection> connections
    ) {
    }

    public record Connection(
            String name,
            String provider,
            boolean connected,
            String detail,
            String state,
            Observation diagnostic
    ) {
        public Connection(
                String name,
                String provider,
                boolean connected,
                String detail
        ) {
            this(name, provider, connected, detail, null, null);
        }
    }
}
