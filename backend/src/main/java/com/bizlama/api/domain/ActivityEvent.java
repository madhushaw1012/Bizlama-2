package com.bizlama.api.domain;

import java.time.Instant;
public record ActivityEvent (String id, String type, String description, Instant occurredAt) {}
