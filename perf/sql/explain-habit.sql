-- HF-26 EXPLAIN for the habitflow DB. SQL text verbatim from the code, ? -> $n, typed as the driver binds them.
-- Every EXPLAIN runs inside BEGIN ... ROLLBACK (the claim takes FOR UPDATE locks), so repeats see the same data.
\set ON_ERROR_STOP on
\pset pager off

-- 1. OutboxStore.CLAIM_SQL (JdbcTemplate; limit = outbox.batch-size = 50)
PREPARE claim(int) AS
SELECT id, aggregate_id, event_type, event_version, payload::text AS payload, attempts
FROM outbox_events
WHERE sent_at IS NULL AND next_attempt_at <= now()
ORDER BY created_at
LIMIT $1
FOR UPDATE SKIP LOCKED;

-- 3. HabitRepository.findByUserId: DERIVED from the captured findByIdAndUserId SQL (Hibernate 6.3.1),
--    with "h1_0.id=? and " removed. No test calls findByUserId, so it could not be captured directly.
PREPARE by_user(bigint) AS
select h1_0.id,h1_0.created_at,h1_0.description,h1_0.frequency,h1_0.name,h1_0.updated_at,h1_0.user_id from habits h1_0 where h1_0.user_id=$1;

-- 4. HabitCompletionRepository.existsByHabitIdAndCompletionDate: CAPTURED (SPRING_JPA_SHOW_SQL=true, ToggleOutboxIT).
PREPARE exists_today(bigint, date, int) AS
select hc1_0.id from habit_completions hc1_0 where hc1_0.habit_id=$1 and hc1_0.completion_date=$2 fetch first $3 rows only;

\echo '#### Q1 claim: run 1'
BEGIN; EXPLAIN (ANALYZE, BUFFERS) EXECUTE claim(50); ROLLBACK;
\echo '#### Q1 claim: run 2'
BEGIN; EXPLAIN (ANALYZE, BUFFERS) EXECUTE claim(50); ROLLBACK;
\echo '#### Q1 claim: run 3'
BEGIN; EXPLAIN (ANALYZE, BUFFERS) EXECUTE claim(50); ROLLBACK;

\echo '#### Q3 findByUserId (derived): run 1'
BEGIN; EXPLAIN (ANALYZE, BUFFERS) EXECUTE by_user(1000); ROLLBACK;
\echo '#### Q3 findByUserId (derived): run 2'
BEGIN; EXPLAIN (ANALYZE, BUFFERS) EXECUTE by_user(1000); ROLLBACK;
\echo '#### Q3 findByUserId (derived): run 3'
BEGIN; EXPLAIN (ANALYZE, BUFFERS) EXECUTE by_user(1000); ROLLBACK;

\echo '#### Q4 existsByHabitIdAndCompletionDate: run 1'
BEGIN; EXPLAIN (ANALYZE, BUFFERS) EXECUTE exists_today(1000, current_date, 1); ROLLBACK;
\echo '#### Q4 existsByHabitIdAndCompletionDate: run 2'
BEGIN; EXPLAIN (ANALYZE, BUFFERS) EXECUTE exists_today(1000, current_date, 1); ROLLBACK;
\echo '#### Q4 existsByHabitIdAndCompletionDate: run 3'
BEGIN; EXPLAIN (ANALYZE, BUFFERS) EXECUTE exists_today(1000, current_date, 1); ROLLBACK;
