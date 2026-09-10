package com.bizlama.api.config;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

/** Converts domain instants to a PostgreSQL JDBC-supported UTC timestamp value. */
public final class JdbcTimestamp {

    private JdbcTimestamp() {
    }

    public static OffsetDateTime utc(Instant instant) {
        return instant == null
                ? null
                : OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
