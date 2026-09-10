package com.bizlama.api.system;

import com.bizlama.api.analytics.raw.RawAnalyticsHealthIndicator;
import com.bizlama.api.analytics.raw.RawAnalyticsProperties;
import com.bizlama.api.receipts.ReceiptStorageHealthIndicator;
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
    private final boolean aiEnabled;
    private final String authMode;
    private final ReceiptStorageHealthIndicator receiptStorageHealth;
    private final RawAnalyticsProperties rawAnalyticsProperties;
    private final RawAnalyticsHealthIndicator rawAnalyticsHealth;

    public SystemStatusController(
            JdbcClient jdbc,
            Environment springEnvironment,
            @Value("${spring.datasource.url}") String databaseUrl,
            @Value("${bizlama.receipts.storage-mode:local}") String receiptStorage,
            @Value("${bizlama.receipts.ai-enabled:false}") boolean aiEnabled,
            @Value("${bizlama.auth.mode:local}") String authMode,
            ReceiptStorageHealthIndicator receiptStorageHealth,
            RawAnalyticsProperties rawAnalyticsProperties,
            RawAnalyticsHealthIndicator rawAnalyticsHealth
    ) {
        this.jdbc = jdbc;
        this.springEnvironment = springEnvironment;
        this.databaseUrl = databaseUrl;
        this.receiptStorage = receiptStorage;
        this.aiEnabled = aiEnabled;
        this.authMode = authMode;
        this.receiptStorageHealth = receiptStorageHealth;
        this.rawAnalyticsProperties = rawAnalyticsProperties;
        this.rawAnalyticsHealth = rawAnalyticsHealth;
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
                        new Connection(
                                "Receipt recognition",
                                aiEnabled
                                        ? "Vertex AI configured"
                                        : "Manual review mode",
                                !aiEnabled,
                                aiEnabled
                                        ? "Optional provider is checked per request; fallback remains active"
                                        : "Deterministic fallback is active"
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
            String detail
    ) {
    }
}
