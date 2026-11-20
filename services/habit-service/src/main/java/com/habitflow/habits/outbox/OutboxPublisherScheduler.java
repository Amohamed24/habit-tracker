package com.habitflow.habits.outbox;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * The poll loop. Separate from {@link OutboxPublisher} so {@code outbox.publisher.enabled=false} removes
 * only the schedule: tests still get the publisher bean and call {@code publishBatch()} directly.
 */
@Component
@RequiredArgsConstructor
@Slf4j
@ConditionalOnProperty(name = "outbox.publisher.enabled", havingValue = "true", matchIfMissing = true)
public class OutboxPublisherScheduler {

    private final OutboxPublisher publisher;

    @Scheduled(fixedDelayString = "${outbox.poll-ms:500}")
    public void poll() {
        try {
            publisher.publishBatch();
        } catch (RuntimeException e) {
            // The batch rolled back, so every claimed row is still unsent; the next poll retries it.
            log.warn("Outbox publish batch failed; rows stay unsent for the next poll: {}", e.toString());
        }
    }
}
