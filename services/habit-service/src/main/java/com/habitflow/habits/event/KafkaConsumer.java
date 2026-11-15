package com.habitflow.habits.event;

import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;

@Service
@Slf4j
public class KafkaConsumer {
    
    @KafkaListener(topics = "user-events", groupId = "habit-service-group")
    public void handleUserRegisteredEvent(UserRegisteredEvent event) {
        log.info("Received UserRegisteredEvent: {}", event);
        
        // Example: Could create default habits for new users
        // Could send welcome notification
        // Could update analytics
        
        log.info("New user registered: {} (ID: {})", event.getEmail(), event.getUserId());
    }
}