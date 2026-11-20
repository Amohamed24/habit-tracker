package com.habitflow.habits.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Turns on @Scheduled processing for the outbox publisher. Kept off the application class so tests
 * (and operators) can switch the poll loop off with {@code outbox.publisher.enabled=false}.
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "outbox.publisher.enabled", havingValue = "true", matchIfMissing = true)
public class SchedulingConfig {
}
