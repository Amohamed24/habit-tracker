package com.habitflow.habits.outbox;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.habitflow.habits.entity.OutboxEvent;
import com.habitflow.habits.event.EventEnvelope;
import com.habitflow.habits.repository.OutboxEventRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Writes a v1 envelope to the outbox inside the caller's transaction, so the event commits or
 * rolls back together with the domain change. MANDATORY: calling it without a transaction fails
 * instead of silently auto-committing the event on its own.
 */
@Component
@RequiredArgsConstructor
public class OutboxWriter {

    // Boot's ObjectMapper: the same configuration KafkaProducer serializes envelopes with.
    private final ObjectMapper objectMapper;
    private final OutboxEventRepository outboxEventRepository;

    @Transactional(propagation = Propagation.MANDATORY)
    public OutboxEvent write(String aggregateType, String aggregateId, String eventType, Object payload) {
        EventEnvelope<Object> envelope = EventEnvelope.v1(eventType, aggregateId, payload);

        String json;
        try {
            json = objectMapper.writeValueAsString(envelope);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(
                    "Failed to serialize " + eventType + " event " + envelope.eventId(), e);
        }

        return outboxEventRepository.save(OutboxEvent.builder()
                .id(envelope.eventId())
                .aggregateType(aggregateType)
                .aggregateId(aggregateId)
                .eventType(eventType)
                .eventVersion(envelope.eventVersion())
                .payload(json)
                .occurredAt(envelope.occurredAt())
                .build());
    }
}
