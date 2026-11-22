-- HF-26 seed for the insights DB (throwaway hf26-explain container only).
-- Disclosed additions (not Jira volumes): 100,000 processed_events, 60,000 daily_completion_counts.
\set ON_ERROR_STOP on

-- One ledger row per sent event (mirrors the 100,000 sent outbox rows; separate DB, so ids need not match).
INSERT INTO processed_events (consumer, event_id, processed_at)
SELECT 'insights-service', gen_random_uuid(), now() - interval '30 days' + g * interval '25.92 seconds'
FROM generate_series(1, 100000) g;

-- 2,000 users x 30 days. (1000, current_date) exists, so the upsert takes the conflict -> DO UPDATE path.
INSERT INTO daily_completion_counts (user_id, completion_date, completed_count, updated_at)
SELECT u, current_date - d, ((u + d) % 5) + 1, (current_date - d) + time '20:00'
FROM generate_series(1, 2000) u, generate_series(0, 29) d;
