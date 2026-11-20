package com.habitflow.habits.outbox;

import java.util.UUID;

/**
 * A claimed, unsent outbox row: what the publisher needs to build the Kafka record, plus the current
 * attempts count for the backoff (HF-13).
 */
public record OutboxRow(UUID id, String aggregateId, String eventType, int eventVersion, String payload,
                        int attempts) {
}
