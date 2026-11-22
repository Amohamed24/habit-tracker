package com.habitflow.insights.projection;

import com.fasterxml.jackson.databind.JsonNode;
import com.habitflow.insights.event.EventEnvelope;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;

/**
 * Idempotent apply of habit events to the daily completion projection (R1-10 / HF-17).
 *
 * <p>The {@code processed_events} insert and the projection upsert run in one transaction, so either both
 * land or neither does. Dedupe is {@code ON CONFLICT DO NOTHING} on {@code (consumer, event_id)}: a duplicate
 * inserts 0 rows and writes nothing else. No unique violation (SQLState 23505) is raised on that path, even
 * for concurrent duplicates (the second insert waits for the first and then skips), so nothing here catches one.
 *
 * <p>No clamping at 0: +1/-1 is commutative, so out-of-order delivery converges; a temporary -1 is expected.
 */
@Service
public class ProjectionService {

    /** Default consumer name; HF-18's Kafka group id. */
    public static final String CONSUMER = "insights-service";

    /** The only envelope version this consumer understands (docs/events/*.v1.json). */
    static final int SUPPORTED_EVENT_VERSION = 1;

    private static final String INSERT_PROCESSED = """
            INSERT INTO processed_events (consumer, event_id) VALUES (?, ?)
            ON CONFLICT DO NOTHING""";

    private static final String UPSERT_DAILY_COUNT = """
            INSERT INTO daily_completion_counts (user_id, completion_date, completed_count) VALUES (?, ?, ?)
            ON CONFLICT (user_id, completion_date) DO UPDATE
               SET completed_count = daily_completion_counts.completed_count + EXCLUDED.completed_count,
                   updated_at = clock_timestamp()""";

    private final JdbcTemplate jdbc;

    public ProjectionService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Applies the event as {@link #CONSUMER}. */
    @Transactional
    public ApplyResult apply(EventEnvelope event) {
        return apply(CONSUMER, event);
    }

    /**
     * @throws IllegalArgumentException if {@code consumer}, {@code event_id} or {@code event_type} is null/blank,
     *                                  {@code event_version} is not {@value #SUPPORTED_EVENT_VERSION},
     *                                  or a counted event lacks an integral payload {@code userId} or a valid
     *                                  {@code completionDate} (all checked before any write)
     */
    @Transactional
    public ApplyResult apply(String consumer, EventEnvelope event) {
        if (consumer == null || consumer.isBlank()) {
            throw new IllegalArgumentException("consumer is null or blank");
        }
        if (event == null || event.eventId() == null) {
            throw new IllegalArgumentException("event_id is null");
        }
        // HF-20: not retryable, so it goes straight to the DLT. A missing event_version binds as 0 and lands here too.
        if (event.eventVersion() != SUPPORTED_EVENT_VERSION) {
            throw new IllegalArgumentException(
                    "event " + event.eventId() + ": unsupported event_version " + event.eventVersion());
        }
        if (event.eventType() == null || event.eventType().isBlank()) {
            throw new IllegalArgumentException("event " + event.eventId() + ": event_type is null or blank");
        }
        int delta = deltaFor(event.eventType());
        long userId = 0;
        LocalDate completionDate = null;
        if (delta != 0) {
            userId = requiredUserId(event);
            completionDate = requiredCompletionDate(event);
        }

        int inserted = jdbc.update(INSERT_PROCESSED, consumer, event.eventId());
        if (inserted == 0) {
            return ApplyResult.DUPLICATE;
        }
        if (delta != 0) {
            jdbc.update(UPSERT_DAILY_COUNT, userId, completionDate, delta);
        }
        return ApplyResult.APPLIED;
    }

    private static int deltaFor(String eventType) {
        if (EventEnvelope.HABIT_COMPLETED.equals(eventType)) {
            return 1;
        }
        if (EventEnvelope.HABIT_COMPLETION_UNDONE.equals(eventType)) {
            return -1;
        }
        return 0;
    }

    private static long requiredUserId(EventEnvelope event) {
        JsonNode node = event.payload() == null ? null : event.payload().get("userId");
        if (node == null) {
            throw new IllegalArgumentException(event.eventType() + " " + event.eventId() + ": payload.userId missing");
        }
        // isIntegralNumber rejects 1.5 (canConvertToLong alone accepts it and truncates) and "1" (a string).
        if (!node.isIntegralNumber() || !node.canConvertToLong()) {
            throw new IllegalArgumentException(
                    event.eventType() + " " + event.eventId() + ": payload.userId is not an integral long");
        }
        return node.asLong();
    }

    /** The completion's own local date from the payload, not occurred_at, so a late event lands on the right day. */
    private static LocalDate requiredCompletionDate(EventEnvelope event) {
        JsonNode node = event.payload() == null ? null : event.payload().get("completionDate");
        if (node == null || !node.isTextual()) {
            throw new IllegalArgumentException(
                    event.eventType() + " " + event.eventId() + ": payload.completionDate missing");
        }
        try {
            return LocalDate.parse(node.asText());
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException(
                    event.eventType() + " " + event.eventId() + ": payload.completionDate invalid", e);
        }
    }
}
