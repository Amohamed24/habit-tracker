package com.habitflow.habits.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

@Configuration
public class KafkaConfig {

    /** The topic the outbox publisher writes to; also created here at startup. */
    public static final String HABIT_EVENTS_TOPIC = "habit-events";

    @Bean
    public NewTopic habitEventsTopic() {
        return TopicBuilder.name(HABIT_EVENTS_TOPIC)
                .partitions(3)
                .replicas(1)
                .build();
    }
}
