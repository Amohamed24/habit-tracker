package com.habitflow.habits.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

@Configuration
public class KafkaConfig {
    
    @Bean
    public NewTopic habitEventsTopic() {
        return TopicBuilder.name("habit-events")
                .partitions(3)
                .replicas(1)
                .build();
    }
}