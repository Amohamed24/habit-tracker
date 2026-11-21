-- HF-17 (R1-10): projection schema + idempotency ledger. Once applied anywhere, never edit; write V2.

-- One row per (consumer, event) applied. The PK is the dedupe key: same event_id under a
-- different consumer name is a separate row.
CREATE TABLE processed_events (
    consumer     varchar(100) NOT NULL,
    event_id     uuid         NOT NULL,
    processed_at timestamptz  NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY (consumer, event_id)
);

-- Daily completion count per user. No CHECK (completed_count >= 0): +1/-1 is commutative, so an
-- undone delivered before its completed briefly reads -1 and converges to 0.
CREATE TABLE daily_completion_counts (
    user_id         bigint      NOT NULL,
    completion_date date        NOT NULL,
    completed_count int         NOT NULL DEFAULT 0,
    updated_at      timestamptz NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY (user_id, completion_date)
);
