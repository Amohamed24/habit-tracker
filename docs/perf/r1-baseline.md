# R1 performance baseline (HF-26 / R1-17b)

Query plans for the four R1 hot queries at realistic volume, the HF-25 k6 numbers, and a ranked list of fixes.
**No fix is applied here** (that is R1-17c / HF-27).

- Raw evidence: [`r1-explain-raw.txt`](r1-explain-raw.txt) (one real run of `./perf/sql/explain.sh`,
  committed as written) and [`r1-k6-before.txt`](r1-k6-before.txt) (HF-25).
- **Number rule:** every number below is quoted from one of those two files. Nothing is extrapolated.

## 1. Setup

### Hardware
- MacBookPro17,1, Apple M1, 8 CPUs, 8 GiB RAM.
- Docker server 29.1.3, VM with 8 CPUs and 4630360064 bytes of memory.
- The other containers running during the EXPLAIN run are listed in the raw file. They are the habitflow
  stack only; the Supabase stack was not running.

### Database
- **Isolation:** a throwaway container `hf26-explain`, run with `--network none`, no published port and no
  volume. It is removed by an EXIT trap.
  - It uses image `sha256:b3968e348b48…`, the same image ID as `habitflow-db` and `habitflow-insights-db`.
  - PostgreSQL 15.15.
- **Stock config, identical on both,** per the `pg_settings` lines for live `habitflow-db` and the throwaway
  in the raw file:

  | Setting | Value |
  |---|---|
  | `shared_buffers` | 16384 × 8kB |
  | `work_mem` | 4096 kB |
  | `effective_cache_size` | 524288 × 8kB |
  | `random_page_cost` | 4 |
  | `jit` | on |
  | `plan_cache_mode` | auto |

- **Schema:** the real migration files, applied with `psql -f` in version order:
  - habit-service `V1__baseline.sql`, `V2__outbox.sql`
  - insights-service `V1__projection.sql`

  There is no Flyway history table; the DDL is the same. The resulting index list in the raw file matches
  the live DBs, except that the unique index on `habit_completions (habit_id, completion_date)` has a
  different name live (Hibernate-era `uk1jmmm9bn1u8tqv76tvux1htde`). The definition is the same.
- **Live DBs untouched.** Live counts and container start times were taken before and after the run, read
  only, and are identical (raw file, `live proof`):

  | | habits | habit_completions | outbox_events | outbox unsent | processed_events | daily_completion_counts |
  |---|---|---|---|---|---|---|
  | before | 79 | 58 | 5948 | 0 | 5953 | 70 |
  | after  | 79 | 58 | 5948 | 0 | 5953 | 70 |

  - `habitflow-db` started at `2026-11-19T17:07:01.631843379Z`, both before and after.
  - `habitflow-insights-db` started at `2026-11-21T12:58:17.450662215Z`, both before and after.

### Seed
The seed uses `generate_series` and is deterministic, apart from `gen_random_uuid()` ids.
See `perf/sql/seed-habit.sql` and `perf/sql/seed-insights.sql`.

| Table | Rows | Source | Heap / indexes (raw file) |
|---|---|---|---|
| habits | 10,000 | **Jira** | 1064 kB (133 pages) / 240 kB |
| outbox_events | 101,000 (100,000 sent + 1,000 unsent) | **Jira** | 46 MB (5941 pages) / 4336 kB |
| habit_completions | 150,000 | **Disclosed addition** | 8824 kB (1103 pages) / 8256 kB |
| processed_events | 100,000 | **Disclosed addition** | 7480 kB (935 pages) / 7656 kB |
| daily_completion_counts | 60,000 | **Disclosed addition** | 3064 kB (383 pages) / 2096 kB |

- **habits:** 2,000 users × exactly 5 habits, interleaved (`user_id = ((g - 1) % 2000) + 1`). Each user's
  habits are spread across the heap, as they would be when created over time. A uniform count makes any
  user's plan representative.
- **outbox_events:**
  - The envelope and payload keys are copied from a live row, so the average payload is 332 bytes.
  - The 100,000 sent rows are spread evenly over 30 days, with `created_at` rising in insert order.
  - The 1,000 unsent rows are **all due** (`next_attempt_at = created_at`, within the last 50 s). That is the
    worst case for the claim.
- **Disclosed additions** (not Jira volumes), each needed for a meaningful plan:
  - **habit_completions:** 30 days × 10,000 habits at 50%. Without it, the `exists` probe hits an empty
    index. Habit 1000 has a completion today.
  - **processed_events:** one ledger row per sent event, so the `ON CONFLICT` check probes a realistically
    sized PK.
  - **daily_completion_counts:** 2,000 users × 30 days, so the upsert takes the conflict → `DO UPDATE` path
    (user 1000, today).
- `VACUUM (ANALYZE)` ran on both databases before any EXPLAIN.

### Method
- **SQL text is verbatim from the code.** Each statement is `PREPARE`d, with `?` turned into `$n` and typed
  as the driver binds it, then run as `EXPLAIN (ANALYZE, BUFFERS) EXECUTE ...`.
- **Every EXPLAIN runs inside `BEGIN … ROLLBACK`,** so repeats see the same data. The claim takes row locks,
  and the insert and upsert really execute.
- **Each query runs 3 times.** Run 3 is quoted below; all three are in the raw file.
- **No disk reads in any run:** every `Buffers:` line in the raw file is `shared hit`, and there are 0
  `read=` entries. The seed and vacuum had just loaded these pages into memory.
- **Where the Hibernate SQL comes from:**
  - It was captured with `SPRING_JPA_SHOW_SQL=true ./mvnw -B verify -Dit.test=ToggleOutboxIT`
    (Hibernate 6.3.1).
  - `existsByHabitIdAndCompletionDate` is **captured**.
  - `findByUserId` is **derived:** no test calls it, so it is the captured `findByIdAndUserId` statement with
    `h1_0.id=? and` removed. It has the same entity, select list and alias scheme, and the plan-driving
    predicate `user_id = ?` is the same.

## 2. HF-25 k6 baseline (run `20261122T171517Z`, quoted from `r1-k6-before.txt`)
50 VUs for 2 minutes, each iteration doing 1 toggle + 1 list with 1 s think time. Setup created one user and
one habit per VU.

| Metric | avg | med | p(90) | p(95) | p(99) | max |
|---|---|---|---|---|---|---|
| `http_req_duration{endpoint:list}` | 22.57ms | 11.54ms | 54.19ms | **74.84ms** | 150.75ms | 318.08ms |
| `http_req_duration{endpoint:toggle}` | 45.02ms | 28.19ms | 99.03ms | **132.08ms** | 259.32ms | 529.84ms |

- **Thresholds:** list ✓ `p(95)<300`, toggle ✓ `p(95)<300`, and `http_req_failed{phase:load}` ✓ at 0.00%
  (0 out of 11308). k6 exited with code 0.
- **Throughput:** 5654 iterations (43.316157/s). `checks_succeeded` 100.00% (16962 out of 16962).
- **Outbox:**
  - The outbox drained (0 unsent).
  - This run's 5704 rows had `sent_at - created_at` p50/p95/max of 187/542/668 ms.
  - The lag samples taken every 5 s are in the file.
- **Volume at the time:** the live DB held about 80 habits (79 at the time of this report), not 10,000.

### Server-side means during the same window (Prometheus, read only)
- Evaluated at `2026-11-22T17:17:30Z` over `[2m]`; the raw PromQL and JSON are at the end of
  `r1-explain-raw.txt`.
- **These are means only.** The timers have no histogram buckets, so there are no server-side percentiles.
  The `_max` gauges are rolling windows that can include pre-run requests, so they are not used.

```
sum by (job,method,uri,status) (increase(http_server_requests_seconds_sum{uri=~"/api/auth/validate|/api/habits|/api/habits/{id}/toggle"}[2m]))
  / sum by (job,method,uri,status) (increase(http_server_requests_seconds_count{uri=~"/api/auth/validate|/api/habits|/api/habits/{id}/toggle"}[2m]))
```

| Service, endpoint | Mean (s) | Count over the window |
|---|---|---|
| auth-service `GET /api/auth/validate` | 0.004387109583443106 | 11278.534899002885 |
| habit-service `GET /api/habits` | 0.01730004587621359 | 5649.8014455903785 |
| habit-service `POST /api/habits/{id}/toggle` | 0.03378443955663431 | 5649.8014455903785 |

The validate count is about list + toggle: one synchronous validate call per habit request, as
`AuthenticationFilter` → `AuthServiceClient.validateToken` does in the code.

## 3. Plans (run 3 of 3)

### Q1 · Outbox claim (`OutboxStore.CLAIM_SQL`, `$1 = 50`)
```
 Limit  (cost=135.66..136.28 rows=50 width=93) (actual time=1.558..1.591 rows=50 loops=1)
   Buffers: shared hit=163
   ->  LockRows  (cost=135.66..147.86 rows=976 width=93) (actual time=1.558..1.587 rows=50 loops=1)
         Buffers: shared hit=163
         ->  Sort  (cost=135.66..138.10 rows=976 width=93) (actual time=1.519..1.522 rows=50 loops=1)
               Sort Key: created_at
               Sort Method: quicksort  Memory: 439kB
               Buffers: shared hit=63
               ->  Index Scan using outbox_events_unsent_idx on outbox_events  (cost=0.28..103.24 rows=976 width=93) (actual time=0.014..1.235 rows=1000 loops=1)
                     Index Cond: (next_attempt_at <= now())
                     Filter: (sent_at IS NULL)
                     Buffers: shared hit=63
 Planning Time: 0.038 ms
 Execution Time: 1.640 ms
```
- **Scan:** an Index Scan on the partial index `outbox_events_unsent_idx`. The 100,000 sent rows are not in
  that index, so they cost nothing here. This scan reads 63 buffers against the table's 5941 heap pages.
- **Rows:** 976 estimated, 1000 actual. Every unsent row is due, so all of them pass the filter.
- **Sort:** the index is ordered by `next_attempt_at`, but the query orders by `created_at`. So **every due
  row is fetched and fully sorted** (`quicksort`, 439kB) before 50 are kept.
  - `LockRows` sits between `Limit` and `Sort`, so the sort is not a bounded top-N.
  - `LockRows` adds 100 buffer hits (163 − 63) to lock 50 rows.
- **Time:** 1.640 ms execution (run 1: 3.800 ms). This is off the request path; one scheduler thread runs it
  every 500 ms.
- **At larger backlogs:** the scan and sort cover every due row, so cost grows with the backlog, not with the
  batch size. A backlog large enough to exceed `work_mem` (4096 kB) would sort on disk. That case was not
  measured here.

### Q2 · `processed_events` insert + projection upsert (`ProjectionService`, one transaction)
```
 Insert on processed_events  (cost=0.00..0.01 rows=0 width=0) (actual time=0.046..0.046 rows=0 loops=1)
   Conflict Resolution: NOTHING
   Tuples Inserted: 1
   Conflicting Tuples: 0
   Buffers: shared hit=9
   ->  Result  (cost=0.00..0.01 rows=1 width=242) (actual time=0.001..0.001 rows=1 loops=1)
 Planning Time: 0.019 ms
 Execution Time: 0.056 ms
```
```
 Insert on daily_completion_counts  (cost=0.00..0.01 rows=0 width=0) (actual time=0.023..0.023 rows=0 loops=1)
   Conflict Resolution: UPDATE
   Conflict Arbiter Indexes: daily_completion_counts_pkey
   Tuples Inserted: 0
   Conflicting Tuples: 1
   Buffers: shared hit=10
   ->  Result  (cost=0.00..0.01 rows=1 width=24) (actual time=0.001..0.001 rows=1 loops=1)
 Planning Time: 0.020 ms
 Execution Time: 0.028 ms
```
- **Insert:** a single-row insert. `ON CONFLICT DO NOTHING` checks against `processed_events_pkey`
  (100,000 rows): 1 tuple inserted, 0 conflicting, 9 buffers.
- **Upsert:** the conflict → `DO UPDATE` path through the arbiter `daily_completion_counts_pkey`
  (60,000 rows): 1 conflicting tuple updated, 10 buffers.
- **Time:** 0.056 + 0.028 ms (run 1: 0.371 + 0.205 ms). Both are PK-bounded btree probes, so table growth
  only deepens the btree. **Nothing to fix.**

### Q3 · `habits WHERE user_id = ?` (`HabitRepository.findByUserId`, SQL **derived**, `$1 = 1000`)
```
 Seq Scan on habits h1_0  (cost=0.00..258.00 rows=5 width=72) (actual time=0.042..0.372 rows=5 loops=1)
   Filter: (user_id = '1000'::bigint)
   Rows Removed by Filter: 9995
   Buffers: shared hit=133
 Planning Time: 0.018 ms
 Execution Time: 0.374 ms
```
- **Scan:** a **Seq Scan**, as Jira expected. There is no index on `user_id`.
- **Rows:** 5 estimated, 5 actual. **9995 rows are removed by the filter** to return 5.
- **Buffers:** 133, which is the whole heap (133 pages).
- **Time:** 0.374 ms (run 1: 0.971 ms).
- **Growth:** the cost is proportional to **all users' habits**, not this user's. It grows with the size of
  the table while the result stays at 5 rows.
- **Used by list only.** Toggle uses `findByIdAndUserId`, a PK lookup, which is not one of the four plans.

### Q4 · `existsByHabitIdAndCompletionDate` (SQL **captured**, `1000, current_date, 1`)
Captured SQL:
```
select hc1_0.id from habit_completions hc1_0 where hc1_0.habit_id=? and hc1_0.completion_date=? fetch first ? rows only
```
```
 Limit  (cost=0.42..8.44 rows=1 width=8) (actual time=0.009..0.009 rows=1 loops=1)
   Buffers: shared hit=4
   ->  Index Scan using uk_habit_completions_habit_date on habit_completions hc1_0  (cost=0.42..8.44 rows=1 width=8) (actual time=0.008..0.009 rows=1 loops=1)
         Index Cond: ((habit_id = '1000'::bigint) AND (completion_date = '2026-11-22'::date))
         Buffers: shared hit=4
 Planning Time: 0.029 ms
 Execution Time: 0.022 ms
```
- **Scan:** an Index Scan on the unique `(habit_id, completion_date)` index, with Limit 1. Hibernate renders
  no join to `habits`.
- **Rows:** 1 estimated, 1 actual. 4 buffers.
- **Time:** 0.022 ms (run 1: 0.053 ms). This is already optimal as a single statement.
- **How often it runs:**
  - Toggle runs it twice (the decision, then `mapToResponse`).
  - **List runs it once per habit** (`mapToResponse` in a loop), so a list call is 1 + N statements.
- **What EXPLAIN can't show:** it measures only the server-side execution. The cost of N extra statements
  is per-statement round trip and ORM overhead, which an EXPLAIN plan doesn't show.

## 4. Ranked fixes
**Ranking key:** the expected effect on the k6-measured paths (list and toggle latency), weighed against the
change's size and risk. The evidence column cites only numbers above.

| Rank | Fix | Evidence | Expected impact | Ticket |
|---|---|---|---|---|
| 1 | **Validate the JWT locally in habit-service** instead of a synchronous `GET /api/auth/validate` per request | Prometheus: auth's own server-side mean is 0.00439 s per call, on every list (mean 0.01730 s) and every toggle (mean 0.03378 s). 11,278 calls in the window. | Largest measured cost on both k6 paths, and independent of data volume. It also removes auth-service as a runtime dependency of every habit request. | **New ticket (R1b).** It changes the trust model (key distribution, revocation, expiry), so it does not fit R1-17c's "one fix" perf scope. |
| 2 | **Remove the list N+1:** fetch the user's completed-today habit ids in one query instead of one `exists` per habit | Code: 1 + N statements per list. Q4: each probe is already 0.022 ms in the DB, so the cost is the per-statement round trips. | Grows with habits per user. In HF-25's shape (1 habit per user) a list call is 2 statements, so **k6 as written can barely show it**. | New ticket, or HF-27 if you choose it. |
| 3 | **`CREATE INDEX IF NOT EXISTS ... ON habits (user_id)`** (`V3__idx_habits_user_id.sql`) | Q3: Seq Scan, 9995 rows removed for 5, 133 buffers (the whole heap), 0.374 ms at 10,000 habits | Small per call today. It's the only one of the four plans whose cost grows with *total* table size, so it's the scaling fix. **On the live volume (79 habits) a k6 before/after can't show it.** | **HF-27 (R1-17c), recommended:** it's the top-ranked fix that fits R1-17c's form (one migration, provable Seq Scan → index plan change) |
| 4 | **Partial index on `outbox_events (created_at) WHERE sent_at IS NULL`**, so the claim can read rows in order and stop at 50 | Q1: all 1000 due rows fetched and quicksorted (439kB) to keep 50, 1.640 ms | Off the request path; affects outbox lag only. HF-25's outbox p95 was 542 ms with the claim at about 1.6 ms. Matters in a large backlog (e.g. after a broker outage). | New ticket, low priority |
| 5 | **Retention for sent `outbox_events` and old `processed_events`** | Seeded outbox heap is 46 MB for 101,000 rows; Q1 is unaffected (partial index) | Storage and vacuum cost only, with no effect on these four plans | New ticket, later |
| — | Projection insert / upsert | Q2: 0.056 + 0.028 ms, PK arbiters | None needed | — |

**Note for HF-27.** R1-17c says it applies "whichever fix R1-17b ranks first".
- Rank 1 here is the auth hop. It is a design change, not a one-migration perf fix, so this report
  recommends **HF-27 apply rank 3** (the `habits(user_id)` index). Rank 1 should get its own ticket. That
  choice is the ticket owner's to confirm.
- Either way, the HF-25 k6 shape is a poor detector for rank 2 and rank 3: 1 habit per user, about 80 habits
  in the table. HF-27 should decide its k6 volume deliberately and say so, rather than expect a visible p95
  change.

## 5. Known limitations
- **Synthetic, uniform data:** 5 habits per user, a 50% completion rate, all 1,000 pending outbox rows due,
  and random UUIDs.
- **Fresh tables:** freshly `VACUUM ANALYZE`d, with no bloat, and every page already in memory (0 disk
  reads). The live outbox takes an UPDATE per row (`sent_at`), so it carries dead tuples until autovacuum.
- **Plans can differ on the live DB size:** 79 live habits against 10,000 seeded. Small tables favour seq
  scans whatever the indexes.
- **Custom plans:** these are custom plans from `PREPARE`/`EXECUTE` (3 executions, `plan_cache_mode = auto`).
  pgjdbc switches to server-side prepared statements after 5 executions, and Postgres may then choose
  generic plans.
- **Single laptop:** one M1 laptop, Docker Desktop VM shared with the running habitflow stack. One run of
  3 repeats, so there's no variance estimate, and EXPLAIN ANALYZE adds its own timing overhead.
- **`findByUserId` SQL is derived,** not captured (see Method).
- **Migrations ran through `psql -f`,** not Flyway: the same DDL, but no history table.
- **Prometheus gives means, not percentiles.**
- **Toggle's `findByIdAndUserId`** (a PK lookup) was not planned. It is not one of the four Jira queries.

## Reproduce
```bash
./perf/sql/explain.sh          # from the repo root; needs the compose stack up for the live proof + Prometheus
```
Only the container `hf26-explain` is created and removed. The live DBs are only read.
