package com.habitflow.habits.event;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.header.Headers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.json.JsonTest;
import org.springframework.kafka.core.KafkaTemplate;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** KafkaProducer with a mocked KafkaTemplate and Boot's real ObjectMapper (via @JsonTest). */
@JsonTest
class KafkaProducerTest {

    @Autowired
    ObjectMapper objectMapper;

    private KafkaTemplate<String, String> template;
    private KafkaProducer producer;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        template = mock(KafkaTemplate.class);
        producer = new KafkaProducer(template, objectMapper);
    }

    @Test
    void habitCreated_keyHeadersAndNoTypeId() throws IOException {
        producer.sendHabitCreatedEvent(created());

        ProducerRecord<String, String> record = sent(1).get(0);
        assertThat(record.topic()).isEqualTo("habit-events");
        assertThat(record.key()).isEqualTo("9");
        assertHeaders(record, "habit.created");
        assertThat(body(record).get("event_type").asText()).isEqualTo("habit.created");
        assertThat(body(record).get("payload").get("habitName").asText()).isEqualTo("Read 20 pages");
    }

    @Test
    void eventIdHeader_matchesBody() throws IOException {
        producer.sendHabitCreatedEvent(created());

        ProducerRecord<String, String> record = sent(1).get(0);
        JsonNode body = body(record);
        assertThat(header(record.headers(), "event_id")).isEqualTo(body.get("event_id").asText());
        assertThat(header(record.headers(), "event_version")).isEqualTo(body.get("event_version").asText());
        assertThat(record.key()).isEqualTo(body.get("aggregate_id").asText());
    }

    @Test
    void eventId_uniquePerSend() throws IOException {
        producer.sendHabitCreatedEvent(created());
        producer.sendHabitCreatedEvent(created());

        List<ProducerRecord<String, String>> records = sent(2);
        String first = header(records.get(0).headers(), "event_id");
        String second = header(records.get(1).headers(), "event_id");
        assertThat(first).isNotEqualTo(second);
        assertThat(body(records.get(0)).get("event_id").asText())
                .isNotEqualTo(body(records.get(1)).get("event_id").asText());
    }

    @Test
    void serializationFailure_throwsAndSendsNothing() throws JsonProcessingException {
        ObjectMapper failing = mock(ObjectMapper.class);
        when(failing.writeValueAsString(any())).thenThrow(new JsonProcessingException("boom") { });
        KafkaProducer failingProducer = new KafkaProducer(template, failing);

        assertThatThrownBy(() -> failingProducer.sendHabitCreatedEvent(created()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("habit.created")
                .hasCauseInstanceOf(JsonProcessingException.class);
        verifyNoInteractions(template);
    }

    // --- helpers -------------------------------------------------------------------------------

    @SuppressWarnings({"unchecked", "rawtypes"})
    private List<ProducerRecord<String, String>> sent(int expected) {
        ArgumentCaptor<ProducerRecord> captor = ArgumentCaptor.forClass(ProducerRecord.class);
        verify(template, times(expected)).send(captor.capture());
        return (List) captor.getAllValues();
    }

    private static void assertHeaders(ProducerRecord<String, String> record, String eventType) {
        Set<String> keys = new HashSet<>();
        for (Header h : record.headers()) {
            keys.add(h.key());
        }
        assertThat(keys).containsExactlyInAnyOrder("event_type", "event_version", "event_id");
        assertThat(record.headers().lastHeader("__TypeId__")).isNull();
        assertThat(header(record.headers(), "event_type")).isEqualTo(eventType);
        assertThat(header(record.headers(), "event_version")).isEqualTo("1");
        assertThat(UUID.fromString(header(record.headers(), "event_id"))).isNotNull();
    }

    private static String header(Headers headers, String key) {
        return new String(headers.lastHeader(key).value(), StandardCharsets.UTF_8);
    }

    private JsonNode body(ProducerRecord<String, String> record) throws IOException {
        return objectMapper.readTree(record.value());
    }

    private static HabitCreatedEvent created() {
        return HabitCreatedEvent.builder()
                .habitId(9L).userId(1L).habitName("Read 20 pages").frequency("DAILY")
                .createdAt(LocalDateTime.of(2026, 11, 15, 15, 13, 36))
                .build();
    }
}
