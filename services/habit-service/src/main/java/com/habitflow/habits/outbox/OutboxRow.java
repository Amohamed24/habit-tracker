package com.habitflow.habits.outbox;

import java.util.UUID;

/** A claimed, unsent outbox row: just what the publisher needs to build the Kafka record. */
public record OutboxRow(UUID id, String aggregateId, String eventType, int eventVersion, String payload) {
}
