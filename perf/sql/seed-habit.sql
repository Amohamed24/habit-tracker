-- HF-26 seed for the habitflow DB (throwaway hf26-explain container only). Deterministic apart from gen_random_uuid().
-- Jira volumes: 10,000 habits; 100,000 sent + 1,000 pending outbox rows.
-- Disclosed addition: 150,000 habit_completions (so the exists probe hits a populated index).
\set ON_ERROR_STOP on

-- 10,000 habits: 2,000 users x exactly 5 habits, interleaved (user u owns rows u, u+2000, ..., u+8000).
INSERT INTO habits (name, description, frequency, user_id, created_at, updated_at)
SELECT 'habit ' || g, 'seeded by HF-26 explain', 'DAILY', ((g - 1) % 2000) + 1,
       now() - interval '30 days' + (g % 720) * interval '1 hour',
       now() - interval '30 days' + (g % 720) * interval '1 hour'
FROM generate_series(1, 10000) g;

-- Disclosed addition: 30 days x 10,000 habits at 50% => 150,000 completions. Habit 1000 has one for today.
INSERT INTO habit_completions (habit_id, completion_date, created_at)
SELECT h, current_date - d, (current_date - d) + time '08:00'
FROM generate_series(1, 10000) h, generate_series(0, 29) d
WHERE (h + d) % 2 = 0;

-- Outbox rows shaped like the real v1 envelope (keys copied from a live habit.completed / habit.completion_undone row).
-- 100,000 sent, evenly over the last 30 days, created_at rising with insert order.
INSERT INTO outbox_events (id, aggregate_type, aggregate_id, event_type, event_version, payload,
                           occurred_at, created_at, sent_at, attempts, next_attempt_at)
SELECT id, 'habit', h::text, et, 1,
       jsonb_build_object('event_id', id, 'event_type', et, 'event_version', 1,
                          'occurred_at', to_char(ts AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.MS"Z"'),
                          'aggregate_id', h::text,
                          'payload', CASE WHEN et = 'habit.completed'
                              THEN jsonb_build_object('userId', u, 'habitId', h, 'habitName', 'habit ' || h,
                                                      'completedAt', to_char(ts AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.US'),
                                                      'completionDate', to_char(ts AT TIME ZONE 'UTC', 'YYYY-MM-DD'))
                              ELSE jsonb_build_object('userId', u, 'habitId', h,
                                                      'completionDate', to_char(ts AT TIME ZONE 'UTC', 'YYYY-MM-DD')) END),
       ts, ts, ts + interval '200 ms', 0, ts
FROM (SELECT gen_random_uuid() AS id, ((g - 1) % 10000) + 1 AS h, ((((g - 1) % 10000) + 1) - 1) % 2000 + 1 AS u,
             CASE WHEN g % 2 = 0 THEN 'habit.completed' ELSE 'habit.completion_undone' END AS et,
             now() - interval '30 days' + g * interval '25.92 seconds' AS ts
      FROM generate_series(1, 100000) g) s;

-- 1,000 pending, all due (next_attempt_at = created_at, within the last 50 s): the claim's worst case.
INSERT INTO outbox_events (id, aggregate_type, aggregate_id, event_type, event_version, payload,
                           occurred_at, created_at, sent_at, attempts, next_attempt_at)
SELECT id, 'habit', h::text, 'habit.completed', 1,
       jsonb_build_object('event_id', id, 'event_type', 'habit.completed', 'event_version', 1,
                          'occurred_at', to_char(ts AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.MS"Z"'),
                          'aggregate_id', h::text,
                          'payload', jsonb_build_object('userId', u, 'habitId', h, 'habitName', 'habit ' || h,
                                                        'completedAt', to_char(ts AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.US'),
                                                        'completionDate', to_char(ts AT TIME ZONE 'UTC', 'YYYY-MM-DD'))),
       ts, ts, NULL, 0, ts
FROM (SELECT gen_random_uuid() AS id, g AS h, (g - 1) % 2000 + 1 AS u,
             now() - (1000 - g) * interval '50 ms' AS ts
      FROM generate_series(1, 1000) g) s;
