package com.habitflow.insights.event;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.UUID;

/**
 * Insights-side copy of habit-service's v1 wire envelope ({@code com.habitflow.habits.event.EventEnvelope}).
 * Duplicated, not imported: there is no shared module. Contract fixtures: docs/events/*.v1.json (repo root),
 * checked by EventEnvelopeContractTest.
 *
 * <p>The payload stays a {@link JsonNode}: one consumer handles several event types, and a type this service
 * doesn't project ("recorded, no effect") needs no payload class at all.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record EventEnvelope(
        @JsonProperty("event_id") UUID eventId,
        @JsonProperty("event_type") String eventType,
        @JsonProperty("event_version") int eventVersion,
        @JsonProperty("occurred_at") Instant occurredAt,
        @JsonProperty("aggregate_id") String aggregateId,
        @JsonProperty("payload") JsonNode payload) {

    public static final String HABIT_CREATED = "habit.created";
    public static final String HABIT_COMPLETED = "habit.completed";
    public static final String HABIT_COMPLETION_UNDONE = "habit.completion_undone";
}
