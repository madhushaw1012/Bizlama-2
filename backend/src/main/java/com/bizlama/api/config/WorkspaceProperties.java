package com.bizlama.api.config;

import java.time.ZoneId;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Explicit operational scope for the current single-workspace deployment.
 *
 * <p>Local mode points at the labelled demo seed. Cloud mode deliberately has
 * no fallback values and must name the provisioned kitchen and location.</p>
 */
@Component
@ConfigurationProperties(prefix = "bizlama.workspace")
public class WorkspaceProperties {

    private String kitchenId;
    private String locationId;
    private String timeZone = "UTC";

    public String kitchenId() {
        return require(kitchenId, "BIZLAMA_KITCHEN_ID");
    }

    public void setKitchenId(String kitchenId) {
        this.kitchenId = kitchenId;
    }

    public String locationId() {
        return require(locationId, "BIZLAMA_LOCATION_ID");
    }

    public void setLocationId(String locationId) {
        this.locationId = locationId;
    }

    public ZoneId zoneId() {
        return ZoneId.of(require(timeZone, "BIZLAMA_TIME_ZONE"));
    }

    public void setTimeZone(String timeZone) {
        this.timeZone = timeZone;
    }

    private static String require(String value, String environmentName) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(
                    environmentName + " must be configured."
            );
        }
        return value.trim();
    }
}
