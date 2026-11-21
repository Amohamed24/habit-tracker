package com.habitflow.insights.listener;

import com.habitflow.insights.config.KafkaConfig;
import com.habitflow.insights.event.EventEnvelope;
import com.habitflow.insights.projection.ApplyResult;
import com.habitflow.insights.projection.ProjectionService;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Consumes {@code habit-events} into the daily completion projection (R1-11 / HF-18).
 *
 * <p>The group id is {@link ProjectionService#CONSUMER}, the same name {@code apply} dedupes on, so the two
 * can't drift. Offsets are committed by the container (default BATCH ack mode) only after this method has
 * returned, i.e. after {@code apply}'s transaction committed; a redelivery after a crash is a DUPLICATE.
 * Deserializer settings live in {@code application.yml}. Error handling is Spring Kafka's default until HF-19/20.
 */
@Component
public class HabitEventsListener {

    private static final Logger log = LoggerFactory.getLogger(HabitEventsListener.class);

    private final ProjectionService projection;

    public HabitEventsListener(ProjectionService projection) {
        this.projection = projection;
    }

    @KafkaListener(topics = KafkaConfig.HABIT_EVENTS_TOPIC, groupId = ProjectionService.CONSUMER)
    public void onEvent(ConsumerRecord<String, EventEnvelope> record) {
        EventEnvelope event = record.value();
        ApplyResult result = projection.apply(event);
        log.info("habit-events {}-{}: event_id={} event_type={} {}",
                record.partition(), record.offset(), event.eventId(), event.eventType(), result);
    }
}
