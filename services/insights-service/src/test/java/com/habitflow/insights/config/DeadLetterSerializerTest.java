package com.habitflow.insights.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.habitflow.insights.event.EventEnvelope;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.support.serializer.DelegatingByTypeSerializer;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** The DLT value serializer (HF-19): every value type the recoverer can hand it, including a tombstone. */
class DeadLetterSerializerTest {

    // Same settings Boot applies to its ObjectMapper (JavaTimeModule, ISO dates).
    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    private final DelegatingByTypeSerializer serializer = DeadLetterConfig.dltValueSerializer(mapper);

    @Test
    void byteArray_passesThroughUnchanged() {
        byte[] raw = "{\"event_id\": ".getBytes(StandardCharsets.UTF_8);
        assertThat(serializer.serialize("t", new RecordHeaders(), raw)).isEqualTo(raw);
    }

    @Test
    void string_isUtf8() {
        assertThat(serializer.serialize("t", new RecordHeaders(), "héllo"))
                .isEqualTo("héllo".getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void envelope_isWireShapedJson_withoutTypeHeader() throws Exception {
        UUID id = UUID.randomUUID();
        EventEnvelope env = new EventEnvelope(id, EventEnvelope.HABIT_COMPLETED, 1,
                Instant.parse("2026-11-15T15:13:36.932Z"), "9", mapper.createObjectNode().put("userId", 7));
        RecordHeaders headers = new RecordHeaders();

        JsonNode json = mapper.readTree(serializer.serialize("t", headers, env));

        assertThat(json.get("event_id").asText()).isEqualTo(id.toString());
        assertThat(json.get("event_type").asText()).isEqualTo("habit.completed");
        assertThat(json.get("occurred_at").asText()).isEqualTo("2026-11-15T15:13:36.932Z");
        assertThat(json.get("payload").get("userId").asLong()).isEqualTo(7);
        assertThat(headers.lastHeader("__TypeId__")).isNull();
    }

    @Test
    void nullValue_tombstone_serializesToNull() {
        assertThat(serializer.serialize("t", new RecordHeaders(), null)).isNull();
    }
}
