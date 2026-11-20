package com.habitflow.habits.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

/** One row of the transactional outbox (V2__outbox.sql). Published later by the outbox publisher (R1-06a). */
@Entity
@Table(name = "outbox_events")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OutboxEvent {

    /** The envelope's event_id, assigned by the writer (not generated). */
    @Id
    private UUID id;

    @Column(name = "aggregate_type", nullable = false)
    private String aggregateType;

    @Column(name = "aggregate_id", nullable = false)
    private String aggregateId;

    @Column(name = "event_type", nullable = false)
    private String eventType;

    @Column(name = "event_version", nullable = false)
    private int eventVersion;

    /** The full serialized v1 envelope. Stored as jsonb, so key order and whitespace are not preserved. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload", nullable = false, columnDefinition = "jsonb")
    private String payload;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "sent_at")
    private Instant sentAt;

    @Column(name = "attempts", nullable = false)
    private int attempts;

    /**
     * Set by the database (DEFAULT now()), never by the app: the publisher's claim compares it with
     * Postgres now(), so an app clock ahead of the DB would otherwise delay every publish by the skew.
     * Not refreshed after insert, so it reads null on a freshly saved entity.
     */
    @Column(name = "next_attempt_at", nullable = false, insertable = false, updatable = false)
    private Instant nextAttemptAt;

    @Column(name = "last_error")
    private String lastError;

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = Instant.now();
    }
}
