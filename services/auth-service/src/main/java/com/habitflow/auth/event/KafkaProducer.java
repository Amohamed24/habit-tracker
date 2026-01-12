package com.habitflow.auth.event;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class KafkaProducer {
    
    private final KafkaTemplate<String, Object> kafkaTemplate;
    
    private static final String USER_EVENTS_TOPIC = "user-events";
    
    public void sendUserRegisteredEvent(UserRegisteredEvent event) {
        log.info("Sending UserRegisteredEvent: {}", event);
        kafkaTemplate.send(USER_EVENTS_TOPIC, event.getUserId().toString(), event);
    }
}