package com.habitflow.insights.event;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Contract: the shared fixtures in docs/events/ (repo root, on the test classpath under events/ via pom.xml)
 * deserialize into insights' {@link EventEnvelope} with every field intact.
 */
class EventEnvelopeContractTest {

    private static final ObjectMapper MAPPER = JsonMapper.builder().findAndAddModules().build();

    @Test
    void habitCreatedFixture_deserializesEveryEnvelopeField() throws IOException {
        EventEnvelope e = read("habit.created.v1.json");

        assertThat(e.eventId()).isEqualTo(UUID.fromString("6f1c2a9e-8b7d-4e21-9c55-0a1b2c3d4e5f"));
        assertThat(e.eventType()).isEqualTo(EventEnvelope.HABIT_CREATED);
        assertThat(e.eventVersion()).isEqualTo(1);
        assertThat(e.occurredAt()).isEqualTo(Instant.parse("2026-11-15T15:13:36.932Z"));
        assertThat(e.aggregateId()).isEqualTo("9");
        assertThat(e.payload().path("habitId").asLong()).isEqualTo(9);
        assertThat(e.payload().path("userId").asLong()).isEqualTo(1);
    }

    @Test
    void habitCompletedFixture_deserializes_withUserIdAndCompletionDate() throws IOException {
        EventEnvelope e = read("habit.completed.v1.json");

        assertThat(e.eventId()).isEqualTo(UUID.fromString("0b9d7e3a-1c2f-4a5b-8d6e-7f8091a2b3c4"));
        assertThat(e.eventType()).isEqualTo(EventEnvelope.HABIT_COMPLETED);
        assertThat(e.eventVersion()).isEqualTo(1);
        assertThat(e.occurredAt()).isEqualTo(Instant.parse("2026-11-15T15:13:36.932Z"));
        assertThat(e.aggregateId()).isEqualTo("9");
        assertThat(e.payload().path("userId").isIntegralNumber()).isTrue();
        assertThat(e.payload().path("userId").asLong()).isEqualTo(1);
        assertThat(e.payload().path("completionDate").asText()).isEqualTo("2026-11-15");
    }

    @Test
    void habitCompletionUndoneFixture_deserializes_withUserIdAndCompletionDate() throws IOException {
        EventEnvelope e = read("habit.completion_undone.v1.json");

        assertThat(e.eventId()).isEqualTo(UUID.fromString("5e2c4b1a-9d3f-4e7a-b6c8-1a2b3c4d5e6f"));
        assertThat(e.eventType()).isEqualTo(EventEnvelope.HABIT_COMPLETION_UNDONE);
        assertThat(e.eventVersion()).isEqualTo(1);
        assertThat(e.occurredAt()).isEqualTo(Instant.parse("2026-11-15T18:02:11.407Z"));
        assertThat(e.aggregateId()).isEqualTo("9");
        assertThat(e.payload().path("userId").isIntegralNumber()).isTrue();
        assertThat(e.payload().path("userId").asLong()).isEqualTo(1);
        assertThat(e.payload().path("completionDate").asText()).isEqualTo("2026-11-15");
    }

    @Test
    void unknownEnvelopeField_isIgnored() throws IOException {
        String json = """
                {"event_id":"0b9d7e3a-1c2f-4a5b-8d6e-7f8091a2b3c4","event_type":"habit.completed",
                 "event_version":1,"occurred_at":"2026-11-15T15:13:36.932Z","aggregate_id":"9",
                 "trace_id":"abc","payload":{"userId":1,"completionDate":"2026-11-15"}}""";

        EventEnvelope e = MAPPER.readValue(json, EventEnvelope.class);

        assertThat(e.eventType()).isEqualTo(EventEnvelope.HABIT_COMPLETED);
        assertThat(e.payload().path("completionDate").asText()).isEqualTo("2026-11-15");
    }

    private static EventEnvelope read(String fixture) throws IOException {
        try (InputStream in = EventEnvelopeContractTest.class.getResourceAsStream("/events/" + fixture)) {
            assertThat(in).as("fixture events/%s on the test classpath", fixture).isNotNull();
            return MAPPER.readValue(in, EventEnvelope.class);
        }
    }
}
