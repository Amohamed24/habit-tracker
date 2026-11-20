package com.habitflow.habits.outbox;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.PreparedStatement;
import java.util.List;
import java.util.UUID;

/**
 * SQL for the outbox publisher. Runs on the caller's transaction (JpaTransactionManager exposes its
 * connection to JdbcTemplate), so the row locks taken by {@link #claim} last until that commit.
 */
@Component
@RequiredArgsConstructor
public class OutboxStore {

    // payload::text is Postgres's normalized jsonb text (keys reordered, whitespace changed):
    // consumers must parse JSON, never compare strings.
    static final String CLAIM_SQL = """
            SELECT id, aggregate_id, event_type, event_version, payload::text AS payload, attempts
            FROM outbox_events
            WHERE sent_at IS NULL AND next_attempt_at <= now()
            ORDER BY created_at
            LIMIT ?
            FOR UPDATE SKIP LOCKED
            """;

    // clock_timestamp(), not now(): now() is the transaction start, i.e. before the broker acked.
    static final String MARK_SENT_SQL = "UPDATE outbox_events SET sent_at = clock_timestamp() WHERE id = ANY(?)";

    // One UPDATE for all three columns (HF-13). next_attempt_at is written here, never through the entity
    // (OutboxEvent.nextAttemptAt is insertable=false, updatable=false). clock_timestamp(), not now(): the
    // batch may have waited up to outbox.send-timeout-ms since the transaction began, and a delay measured
    // from now() could already be in the past at commit. sent_at is left NULL.
    static final String MARK_FAILED_SQL = """
            UPDATE outbox_events
            SET attempts = attempts + 1,
                last_error = ?,
                next_attempt_at = clock_timestamp() + ? * interval '1 millisecond'
            WHERE id = ? AND sent_at IS NULL
            """;

    /** A row to back off: its id, the (already truncated) error text, and the delay from now. */
    public record Failure(UUID id, String lastError, long delayMillis) {
    }

    private final JdbcTemplate jdbc;

    /** Locks up to {@code limit} due, unsent rows, oldest first, skipping rows another publisher holds. */
    @Transactional(propagation = Propagation.MANDATORY)
    public List<OutboxRow> claim(int limit) {
        return jdbc.query(CLAIM_SQL, (rs, i) -> new OutboxRow(
                rs.getObject("id", UUID.class),
                rs.getString("aggregate_id"),
                rs.getString("event_type"),
                rs.getInt("event_version"),
                rs.getString("payload"),
                rs.getInt("attempts")), limit);
    }

    /** Sets sent_at on the given (acked) rows. No-op for an empty list. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void markSent(List<UUID> ids) {
        if (ids.isEmpty()) {
            return;
        }
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(MARK_SENT_SQL);
            ps.setArray(1, con.createArrayOf("uuid", ids.toArray()));
            return ps;
        });
    }

    /** attempts += 1, last_error, next_attempt_at = DB time + delay, in one batched UPDATE. No-op if empty. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void markFailed(List<Failure> failures) {
        if (failures.isEmpty()) {
            return;
        }
        jdbc.batchUpdate(MARK_FAILED_SQL, failures, failures.size(), (ps, f) -> {
            ps.setString(1, f.lastError());
            ps.setLong(2, f.delayMillis());
            ps.setObject(3, f.id());
        });
    }
}
