package com.habitflow.habits.event;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/**
 * Versioned wire envelope for every event habit-service publishes.
 * Contract fixtures: docs/events/*.v1.json (repo root).
 *
 * @param <T> the event payload (the event's own fields)
 */
public record EventEnvelope<T>(
        @JsonProperty("event_id") UUID eventId,
        @JsonProperty("event_type") String eventType,
        @JsonProperty("event_version") int eventVersion,
        // Always exactly 3 fraction digits in UTC, e.g. 2026-11-15T15:13:36.932Z (and .000Z for 0 ms).
        // Jackson's default ISO_INSTANT would drop the fraction entirely when it is zero.
        @JsonProperty("occurred_at")
        @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ss.SSSX", timezone = "UTC")
        Instant occurredAt,
        @JsonProperty("aggregate_id") String aggregateId,
        @JsonProperty("payload") T payload) {

    public static final int VERSION_1 = 1;

    /** A new v1 envelope with a fresh event_id and occurred_at = now, truncated to milliseconds. */
    public static <T> EventEnvelope<T> v1(String eventType, String aggregateId, T payload) {
        return new EventEnvelope<>(
                UUID.randomUUID(),
                eventType,
                VERSION_1,
                Instant.now().truncatedTo(ChronoUnit.MILLIS),
                aggregateId,
                payload);
    }
}
