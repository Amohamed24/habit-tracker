package com.habitflow.habits.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Turns on @Scheduled processing. Kept off the application class; whether the outbox poll loop runs is
 * decided by {@code outbox.publisher.enabled} on {@link com.habitflow.habits.outbox.OutboxPublisherScheduler}.
 */
@Configuration
@EnableScheduling
public class SchedulingConfig {
}
