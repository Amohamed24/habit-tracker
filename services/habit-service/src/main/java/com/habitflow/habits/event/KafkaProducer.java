package com.habitflow.habits.event;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;

@Service
@RequiredArgsConstructor
@Slf4j
public class KafkaProducer {

    static final String HABIT_EVENTS_TOPIC = "habit-events";
    static final String HABIT_CREATED = "habit.created";

    static final String HEADER_EVENT_TYPE = "event_type";
    static final String HEADER_EVENT_VERSION = "event_version";
    static final String HEADER_EVENT_ID = "event_id";

    // Values are pre-serialized JSON strings (StringSerializer), so no __TypeId__ header is added.
    private final KafkaTemplate<String, String> kafkaTemplate;
    // Boot's ObjectMapper: JavaTimeModule + WRITE_DATES_AS_TIMESTAMPS=false, so dates are ISO strings.
    private final ObjectMapper objectMapper;

    public void sendHabitCreatedEvent(HabitCreatedEvent event) {
        send(HABIT_CREATED, event.getHabitId(), event);
    }

    private void send(String eventType, Long habitId, Object payload) {
        EventEnvelope<Object> envelope = EventEnvelope.v1(eventType, habitId.toString(), payload);

        String json;
        try {
            json = objectMapper.writeValueAsString(envelope);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(
                    "Failed to serialize " + eventType + " event " + envelope.eventId(), e);
        }

        ProducerRecord<String, String> record =
                new ProducerRecord<>(HABIT_EVENTS_TOPIC, envelope.aggregateId(), json);
        record.headers()
                .add(HEADER_EVENT_TYPE, utf8(eventType))
                .add(HEADER_EVENT_VERSION, utf8(String.valueOf(envelope.eventVersion())))
                .add(HEADER_EVENT_ID, utf8(envelope.eventId().toString()));

        log.info("Sending {} event_id={} aggregate_id={}: {}",
                eventType, envelope.eventId(), envelope.aggregateId(), payload);
        // Still fire-and-forget (L3); completions now go through the outbox (R1-05a); createHabit follows in R1-05b.
        kafkaTemplate.send(record);
    }

    private static byte[] utf8(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
