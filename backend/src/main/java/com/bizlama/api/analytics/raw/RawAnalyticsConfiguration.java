package com.bizlama.api.analytics.raw;

import com.google.cloud.bigquery.BigQuery;
import com.google.cloud.bigquery.BigQueryOptions;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConditionalOnProperty(
        prefix = "bizlama.analytics.raw",
        name = "enabled",
        havingValue = "true")
public class RawAnalyticsConfiguration {

    @Bean
    BigQuery rawAnalyticsBigQuery(RawAnalyticsProperties properties) {
        return BigQueryOptions.newBuilder()
                .setProjectId(properties.requiredProjectId())
                .build()
                .getService();
    }
}
