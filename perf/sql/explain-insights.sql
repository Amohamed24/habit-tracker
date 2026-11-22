-- HF-26 EXPLAIN for the insights DB. SQL text verbatim from ProjectionService, ? -> $n.
-- 2a and 2b run in ONE transaction, in apply() order, then ROLLBACK, so repeats see the same data.
\set ON_ERROR_STOP on
\pset pager off

PREPARE ins_processed(varchar, uuid) AS
INSERT INTO processed_events (consumer, event_id) VALUES ($1, $2)
ON CONFLICT DO NOTHING;

PREPARE upsert_daily(bigint, date, int) AS
INSERT INTO daily_completion_counts (user_id, completion_date, completed_count) VALUES ($1, $2, $3)
ON CONFLICT (user_id, completion_date) DO UPDATE
   SET completed_count = daily_completion_counts.completed_count + EXCLUDED.completed_count,
       updated_at = clock_timestamp();

\echo '#### Q2 processed_events insert + projection upsert: run 1'
BEGIN;
EXPLAIN (ANALYZE, BUFFERS) EXECUTE ins_processed('insights-service', '26262626-2626-4626-a626-262626262626');
EXPLAIN (ANALYZE, BUFFERS) EXECUTE upsert_daily(1000, current_date, 1);
ROLLBACK;
\echo '#### Q2 processed_events insert + projection upsert: run 2'
BEGIN;
EXPLAIN (ANALYZE, BUFFERS) EXECUTE ins_processed('insights-service', '26262626-2626-4626-a626-262626262626');
EXPLAIN (ANALYZE, BUFFERS) EXECUTE upsert_daily(1000, current_date, 1);
ROLLBACK;
\echo '#### Q2 processed_events insert + projection upsert: run 3'
BEGIN;
EXPLAIN (ANALYZE, BUFFERS) EXECUTE ins_processed('insights-service', '26262626-2626-4626-a626-262626262626');
EXPLAIN (ANALYZE, BUFFERS) EXECUTE upsert_daily(1000, current_date, 1);
ROLLBACK;
