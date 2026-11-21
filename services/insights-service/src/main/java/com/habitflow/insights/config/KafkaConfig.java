package com.habitflow.insights.config;

import com.habitflow.insights.projection.ProjectionService;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

/**
 * Topic guard (R1-09b / HF-16). The broker has {@code auto.create.topics.enable=true}, so if insights-service
 * started before habit-service, its first metadata request would auto-create {@code habit-events} with a
 * single partition. Declaring the topic here makes KafkaAdmin create it with 3 partitions at startup,
 * whichever service comes up first.
 *
 * <p>Must stay identical to habit-service's {@code config/KafkaConfig.java} (3 partitions, 1 replica):
 * KafkaAdmin leaves an existing topic with the same partition count alone, so the two beans are idempotent.
 */
@Configuration
public class KafkaConfig {

    /**
     * Duplicated, not imported: there is no shared module. Must match habit-service's
     * {@code KafkaConfig.HABIT_EVENTS_TOPIC}; the event contracts are in {@code docs/events/}.
     */
    public static final String HABIT_EVENTS_TOPIC = "habit-events";
    public static final int HABIT_EVENTS_PARTITIONS = 3;

    /**
     * Consumer-specific dead-letter topic (R1-12a / HF-19): {@code habit-events.insights-service.DLT}. Built from
     * the group id so it can't drift from it, and so a future consumer of habit-events gets its own DLT.
     */
    public static final String DLT_TOPIC = HABIT_EVENTS_TOPIC + "." + ProjectionService.CONSUMER + ".DLT";

    /** Same as the source, so a dead-lettered record keeps its partition number (see DeadLetterConfig). */
    public static final int DLT_PARTITIONS = HABIT_EVENTS_PARTITIONS;

    @Bean
    public NewTopic habitEventsTopic() {
        return TopicBuilder.name(HABIT_EVENTS_TOPIC)
                .partitions(HABIT_EVENTS_PARTITIONS)
                .replicas(1)
                .build();
    }

    @Bean
    public NewTopic habitEventsDltTopic() {
        return TopicBuilder.name(DLT_TOPIC)
                .partitions(DLT_PARTITIONS)
                .replicas(1)
                .build();
    }
}
