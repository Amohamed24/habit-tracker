package com.habitflow.habits.event;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class KafkaProducer {
    
    private final KafkaTemplate<String, Object> kafkaTemplate;
    
    private static final String HABIT_EVENTS_TOPIC = "habit-events";
    
    public void sendHabitCreatedEvent(HabitCreatedEvent event) {
        log.info("Sending HabitCreatedEvent: {}", event);
        kafkaTemplate.send(HABIT_EVENTS_TOPIC, event.getHabitId().toString(), event);
    }
    
    public void sendHabitCompletedEvent(HabitCompletedEvent event) {
        log.info("Sending HabitCompletedEvent: {}", event);
        kafkaTemplate.send(HABIT_EVENTS_TOPIC, event.getHabitId().toString(), event);
    }
}