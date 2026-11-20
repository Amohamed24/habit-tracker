-- Transactional outbox (R1-05a). Ordinary migration: Flyway applies it exactly once per database.
CREATE TABLE outbox_events (
  id uuid PRIMARY KEY,                          -- = envelope event_id
  aggregate_type varchar(64) NOT NULL,
  aggregate_id varchar(64) NOT NULL,
  event_type varchar(128) NOT NULL,
  event_version int NOT NULL,
  payload jsonb NOT NULL,                       -- the full v1 envelope
  occurred_at timestamptz NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  sent_at timestamptz NULL,
  attempts int NOT NULL DEFAULT 0,
  next_attempt_at timestamptz NOT NULL DEFAULT now(),
  last_error text);

CREATE INDEX outbox_events_unsent_idx ON outbox_events (next_attempt_at) WHERE sent_at IS NULL;
