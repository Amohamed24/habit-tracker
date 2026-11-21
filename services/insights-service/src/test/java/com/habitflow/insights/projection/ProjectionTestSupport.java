package com.habitflow.insights.projection;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.habitflow.insights.event.EventEnvelope;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/** Envelope builders and table reads shared by the projection ITs. Every envelope gets a fresh event_id. */
final class ProjectionTestSupport {

    static final LocalDate DAY = LocalDate.parse("2026-11-15");
    private static final AtomicLong NEXT_USER = new AtomicLong(1000);

    private ProjectionTestSupport() {
    }

    /** A user id no other test uses, so tests can't mask each other even without the truncate. */
    static long freshUserId() {
        return NEXT_USER.incrementAndGet();
    }

    static EventEnvelope event(String type, long userId, LocalDate day) {
        ObjectNode payload = JsonNodeFactory.instance.objectNode()
                .put("habitId", 9)
                .put("userId", userId)
                .put("completionDate", day.toString());
        return new EventEnvelope(UUID.randomUUID(), type, 1, Instant.now(), "9", payload);
    }

    static void truncate(JdbcTemplate jdbc) {
        jdbc.execute("TRUNCATE processed_events, daily_completion_counts");
    }

    /** null when there is no row for (user, day). */
    static Integer count(JdbcTemplate jdbc, long userId, LocalDate day) {
        return jdbc.query("SELECT completed_count FROM daily_completion_counts WHERE user_id = ? AND completion_date = ?",
                rs -> rs.next() ? rs.getInt(1) : null, userId, day);
    }

    static int countRowsForUser(JdbcTemplate jdbc, long userId) {
        return jdbc.queryForObject("SELECT count(*) FROM daily_completion_counts WHERE user_id = ?", Integer.class, userId);
    }

    static int processedRows(JdbcTemplate jdbc, UUID eventId) {
        return jdbc.queryForObject("SELECT count(*) FROM processed_events WHERE event_id = ?", Integer.class, eventId);
    }

    static int processedRows(JdbcTemplate jdbc, String consumer, UUID eventId) {
        return jdbc.queryForObject("SELECT count(*) FROM processed_events WHERE consumer = ? AND event_id = ?",
                Integer.class, consumer, eventId);
    }
}
