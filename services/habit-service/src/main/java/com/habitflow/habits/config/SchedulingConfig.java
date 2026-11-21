package com.habitflow.habits.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/**
 * Turns on @Scheduled processing. Kept off the application class; whether the outbox poll loop runs is
 * decided by {@code outbox.publisher.enabled} on {@link com.habitflow.habits.outbox.OutboxPublisherScheduler}.
 *
 * <p>R1-06b / HF-12: a dedicated single-thread scheduler instead of Boot's auto-configured one, so polls
 * never overlap inside one instance and shutdown waits for an in-flight batch. The bean name
 * {@code taskScheduler} is the one @Scheduled resolves; defining it makes Boot's scheduling auto-config back off.
 */
@Configuration
@EnableScheduling
public class SchedulingConfig {

    static final String THREAD_NAME_PREFIX = "outbox-scheduler-";

    // Above OutboxPublisher's 30 s transaction timeout, so a running batch commits or rolls back first.
    static final int AWAIT_TERMINATION_SECONDS = 35;

    @Bean
    public ThreadPoolTaskScheduler taskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix(THREAD_NAME_PREFIX);
        scheduler.setWaitForTasksToCompleteOnShutdown(true);
        scheduler.setAwaitTerminationSeconds(AWAIT_TERMINATION_SECONDS);
        return scheduler;
    }
}
