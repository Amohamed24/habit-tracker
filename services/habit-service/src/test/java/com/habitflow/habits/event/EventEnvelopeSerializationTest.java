package com.habitflow.habits.event;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.json.JsonTest;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Contract test: envelopes serialized with Boot's ObjectMapper (via @JsonTest) must match the
 * shared fixtures in docs/events/ (repo root), ignoring event_id and time values.
 */
@JsonTest
class EventEnvelopeSerializationTest {

    // Surefire runs with the module directory (services/habit-service) as the working directory.
    private static final Path FIXTURES = Path.of("../../docs/events");

    private static final Pattern UUID_PATTERN =
            Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    private static final Pattern ISO_UTC_MILLIS =
            Pattern.compile("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{3}Z");
    private static final Pattern ISO_LOCAL_DATE_TIME =
            Pattern.compile("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d{1,9})?");

    @Autowired
    ObjectMapper objectMapper;

    @Test
    void habitCreated_matchesFixtureShape() throws IOException {
        JsonNode actual = tree(EventEnvelope.v1("habit.created", "9", created()));
        JsonNode fixture = fixture("habit.created.v1.json");

        assertSameShape(actual, fixture, "$");
        assertThat(withoutVolatile(actual)).isEqualTo(withoutVolatile(fixture));
        assertVolatileFormats(actual, "createdAt");
        assertVolatileFormats(fixture, "createdAt");
    }

    @Test
    void habitCompleted_matchesFixtureShape() throws IOException {
        JsonNode actual = tree(EventEnvelope.v1("habit.completed", "9", completed()));
        JsonNode fixture = fixture("habit.completed.v1.json");

        assertSameShape(actual, fixture, "$");
        assertThat(withoutVolatile(actual)).isEqualTo(withoutVolatile(fixture));
        assertVolatileFormats(actual, "completedAt");
        assertVolatileFormats(fixture, "completedAt");
    }

    @Test
    void roundTrip_preservesFields() throws IOException {
        EventEnvelope<HabitCreatedEvent> createdEnvelope = EventEnvelope.v1("habit.created", "9", created());
        EventEnvelope<HabitCompletedEvent> completedEnvelope = EventEnvelope.v1("habit.completed", "9", completed());

        EventEnvelope<HabitCreatedEvent> createdBack = objectMapper.readValue(
                objectMapper.writeValueAsString(createdEnvelope), new TypeReference<>() { });
        EventEnvelope<HabitCompletedEvent> completedBack = objectMapper.readValue(
                objectMapper.writeValueAsString(completedEnvelope), new TypeReference<>() { });

        assertThat(createdBack).isEqualTo(createdEnvelope);
        assertThat(completedBack).isEqualTo(completedEnvelope);
    }

    @Test
    void noArrayValuesAnywhere() throws IOException {
        assertNoArrays(tree(EventEnvelope.v1("habit.created", "9", created())), "$");
        assertNoArrays(tree(EventEnvelope.v1("habit.completed", "9", completed())), "$");
    }

    @Test
    void occurredAt_isIsoUtcMillis() throws IOException {
        Instant subMillis = Instant.parse("2026-11-15T15:13:36.932886212Z");
        JsonNode explicit = tree(new EventEnvelope<>(UUID.randomUUID(), "habit.created", 1, subMillis, "9", created()));
        assertThat(explicit.get("occurred_at").asText()).isEqualTo("2026-11-15T15:13:36.932Z");

        JsonNode fresh = tree(EventEnvelope.v1("habit.created", "9", created()));
        assertThat(fresh.get("occurred_at").asText()).matches(ISO_UTC_MILLIS);
    }

    @Test
    void occurredAt_zeroMillis_printsDotZeroZeroZero() throws IOException {
        Instant zeroMillis = Instant.parse("2026-11-15T15:13:36Z");
        EventEnvelope<HabitCreatedEvent> envelope =
                new EventEnvelope<>(UUID.randomUUID(), "habit.created", 1, zeroMillis, "9", created());

        String json = objectMapper.writeValueAsString(envelope);
        assertThat(objectMapper.readTree(json).get("occurred_at").asText()).isEqualTo("2026-11-15T15:13:36.000Z");

        EventEnvelope<HabitCreatedEvent> back = objectMapper.readValue(json, new TypeReference<>() { });
        assertThat(back.occurredAt()).isEqualTo(zeroMillis);
    }

    @Test
    void completionDate_isIsoDateString() throws IOException {
        JsonNode completionDate = tree(EventEnvelope.v1("habit.completed", "9", completed()))
                .get("payload").get("completionDate");

        assertThat(completionDate.isTextual()).isTrue();
        assertThat(completionDate.asText()).isEqualTo("2026-11-15");
    }

    /**
     * AC "a null description serializes as null": neither event carries a description field
     * (payload = the current event fields), so this proves the same property on an existing
     * nullable field. Boot's mapper uses Include.ALWAYS, so nulls are written, not dropped.
     */
    @Test
    void nullField_serializesAsNull() throws IOException {
        HabitCreatedEvent noName = created();
        noName.setHabitName(null);

        JsonNode payload = tree(EventEnvelope.v1("habit.created", "9", noName)).get("payload");

        assertThat(payload.has("habitName")).as("null field must be present, not omitted").isTrue();
        assertThat(payload.get("habitName").isNull()).isTrue();
    }

    // --- helpers -------------------------------------------------------------------------------

    private static HabitCreatedEvent created() {
        return HabitCreatedEvent.builder()
                .habitId(9L)
                .userId(1L)
                .habitName("Read 20 pages")
                .frequency("DAILY")
                .createdAt(LocalDateTime.of(2026, 11, 15, 15, 13, 36, 932_886_212))
                .build();
    }

    private static HabitCompletedEvent completed() {
        return HabitCompletedEvent.builder()
                .habitId(9L)
                .userId(1L)
                .habitName("Read 20 pages")
                .completionDate(LocalDate.of(2026, 11, 15))
                .completedAt(LocalDateTime.of(2026, 11, 15, 15, 13, 36, 932_886_212))
                .build();
    }

    private JsonNode tree(Object value) throws IOException {
        return objectMapper.readTree(objectMapper.writeValueAsString(value));
    }

    private JsonNode fixture(String name) throws IOException {
        return objectMapper.readTree(FIXTURES.resolve(name).toFile());
    }

    /** A copy without event_id, occurred_at and the payload's createdAt/completedAt. */
    private static JsonNode withoutVolatile(JsonNode node) {
        ObjectNode copy = node.deepCopy();
        copy.remove(Set.of("event_id", "occurred_at"));
        ((ObjectNode) copy.get("payload")).remove(Set.of("createdAt", "completedAt"));
        return copy;
    }

    private static void assertVolatileFormats(JsonNode node, String payloadTimeField) {
        assertThat(node.get("event_id").asText()).matches(UUID_PATTERN);
        assertThat(node.get("occurred_at").asText()).matches(ISO_UTC_MILLIS);
        assertThat(node.get("payload").get(payloadTimeField).asText()).matches(ISO_LOCAL_DATE_TIME);
    }

    /** Same field names at every level, and the same JSON node type for every leaf. */
    private static void assertSameShape(JsonNode actual, JsonNode expected, String path) {
        assertThat(actual.getNodeType()).as("node type at %s", path).isEqualTo(expected.getNodeType());
        if (expected.isObject()) {
            Set<String> actualFields = new HashSet<>();
            actual.fieldNames().forEachRemaining(actualFields::add);
            Set<String> expectedFields = new HashSet<>();
            expected.fieldNames().forEachRemaining(expectedFields::add);
            assertThat(actualFields).as("fields at %s", path).isEqualTo(expectedFields);
            expectedFields.forEach(f -> assertSameShape(actual.get(f), expected.get(f), path + "." + f));
        }
    }

    private static void assertNoArrays(JsonNode node, String path) {
        assertThat(node.isArray()).as("array at %s", path).isFalse();
        node.fields().forEachRemaining(e -> assertNoArrays(e.getValue(), path + "." + e.getKey()));
    }
}
