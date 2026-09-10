package com.bizlama.api.outbox;

public enum OutboxState {
    PENDING,
    IN_FLIGHT,
    PUBLISHED,
    DEAD_LETTER
}
