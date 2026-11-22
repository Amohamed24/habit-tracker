# Runbook: roll the habitflow database back to before Flyway (HF-6)

> **Documented, never executed.** Steps 1–3 and 5–6 come from the HF-6 rollback procedure (PR #28), which was
> written but never run. Step 4 was added in HF-28 from [`V2__outbox.sql`](../../services/habit-service/src/main/resources/db/migration/V2__outbox.sql)
> and has not been run either. Rehearse on a copy of the volume first.

## When this applies
You want the compose `habitflow` database (container `habitflow-db`) back in the state it had before habit-service
moved to Flyway (HF-6), from a backup taken **before** HF-6 with `pg_dump --clean --if-exists`.

What you lose:
- `--clean` drops and recreates **`users`** too, so every user registered after the backup is gone
  (auth-service shares this database; see the `flyway.table` comment in
  [`habit-service/application.yml`](../../services/habit-service/src/main/resources/application.yml)).
- Step 4 drops `outbox_events`, so any event not yet published is lost.

## Steps
Run from the repo root. On macOS, if `docker` isn't found: `export PATH="/Applications/Docker.app/Contents/Resources/bin:$PATH"`.

1. Stop both services that write to this database:
   ```bash
   docker compose stop habit-service auth-service
   ```
2. Drop Flyway's history table. The pre-Flyway dump doesn't contain it; if it stays, the next boot thinks the
   migrations already ran.
   ```bash
   docker compose exec -T postgres psql -U postgres -d habitflow -v ON_ERROR_STOP=1 \
     -c 'DROP TABLE IF EXISTS flyway_schema_history_habit'
   ```
3. Check that nothing is waiting to be published:
   ```bash
   docker compose exec -T postgres psql -U postgres -d habitflow -At \
     -c 'SELECT count(*) FROM outbox_events WHERE sent_at IS NULL'
   ```
   If it isn't 0, start habit-service again and wait for the publisher to drain it, or accept losing those events.
4. Drop the outbox table. The dump doesn't contain it, so `--clean` leaves it in place, and `V2__outbox.sql` is a
   plain `CREATE TABLE` (not `IF NOT EXISTS`): the next boot of current habit-service would fail on it.
   ```bash
   docker compose exec -T postgres psql -U postgres -d habitflow -v ON_ERROR_STOP=1 \
     -c 'DROP TABLE IF EXISTS outbox_events'
   ```
5. Restore the backup:
   ```bash
   docker compose exec -T postgres psql -U postgres -d habitflow -v ON_ERROR_STOP=1 < <your-backup>.sql
   ```
6. Start again, choosing one:
   - **Current code:** `docker compose up -d habit-service auth-service`. Flyway baselines the existing schema at 0
     and runs V1 (idempotent), V2 and V3 (`baseline-on-migrate: true`, `baseline-version: 0` in
     [`application.yml`](../../services/habit-service/src/main/resources/application.yml); starting states proven by
     [`FlywayBaselineIT`](../../services/habit-service/src/test/java/com/habitflow/habits/FlywayBaselineIT.java)).
   - **Pre-HF-6 code** (`ddl-auto: update`): check out the commit before HF-6 (`79fba58^`) and rebuild habit-service.

Never use `docker compose down -v` here: it deletes the volume you are trying to restore into, and `insights-db`'s too.
