# Runbook: roll the habitflow database back to before Flyway (HF-6)

> **Documented, never executed.**
> - **From HF-6** (the rollback procedure in PR #28, written but never run): steps 2, 3, 5 and 6, and the warning about `users`.
> - **New in HF-28**, derived from [`V2__outbox.sql`](../../services/habit-service/src/main/resources/db/migration/V2__outbox.sql)
>   (the outbox table didn't exist when HF-6 was written): step 1 (drain the outbox while the services still run) and
>   step 4 (drop `outbox_events`, refusing if anything is unsent). Not run either.
>
> Rehearse on a copy of the volume first.

## When this applies
You want the compose `habitflow` database (container `habitflow-db`) back in the state it had before habit-service
moved to Flyway (HF-6), from a backup taken **before** HF-6 with `pg_dump --clean --if-exists`.

What you lose:
- `--clean` drops and recreates **`users`** too, so every user registered after the backup is gone
  (auth-service shares this database; see the `flyway.table` comment in
  [`habit-service/application.yml`](../../services/habit-service/src/main/resources/application.yml)).
- Step 4 drops `outbox_events`. Step 1 makes sure everything in it was published first.

## Steps
Run from the repo root. On macOS, if `docker` isn't found: `export PATH="/Applications/Docker.app/Contents/Resources/bin:$PATH"`.

1. **While the services are still running**, check that nothing is waiting to be published:
   ```bash
   docker compose exec -T postgres psql -U postgres -d habitflow -At \
     -c 'SELECT count(*) FROM outbox_events WHERE sent_at IS NULL'
   ```
   If it isn't 0, wait for the outbox publisher to drain it (it polls every 500 ms and backs off failing rows, up to
   5 minutes) and run the check again. Rows that keep failing: see the README's
   [Outbox: rows that keep failing](../../README.md#outbox-rows-that-keep-failing). Go on only when it reads 0.
2. Stop both services that write to this database:
   ```bash
   docker compose stop habit-service auth-service
   ```
3. Drop Flyway's history table. The pre-Flyway dump doesn't contain it; if it stays, the next boot thinks the
   migrations already ran.
   ```bash
   docker compose exec -T postgres psql -U postgres -d habitflow -v ON_ERROR_STOP=1 \
     -c 'DROP TABLE IF EXISTS flyway_schema_history_habit'
   ```
4. Drop the outbox table. The dump doesn't contain it, so `--clean` leaves it in place, and `V2__outbox.sql` is a
   plain `CREATE TABLE` (not `IF NOT EXISTS`): the next boot of current habit-service would fail on it. The command
   refuses, and drops nothing, if a row became unsent between step 1 and step 2. In that case start habit-service
   again (`docker compose start habit-service`) and go back to step 1.
   ```bash
   docker compose exec -T postgres psql -U postgres -d habitflow -v ON_ERROR_STOP=1 -c "
   DO \$\$ DECLARE n bigint; BEGIN
     IF to_regclass('public.outbox_events') IS NOT NULL THEN
       EXECUTE 'SELECT count(*) FROM outbox_events WHERE sent_at IS NULL' INTO n;
       IF n > 0 THEN RAISE EXCEPTION '% unsent outbox rows: start habit-service and go back to step 1', n; END IF;
     END IF;
   END \$\$;
   DROP TABLE IF EXISTS outbox_events;"
   ```
   (Both statements are sent in one `-c`, so they run as one transaction: the exception rolls back the drop.)
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

Never use `docker compose down -v` here: it deletes the volume you are trying to restore into, and every other
volume of the stack (see the README's [Stop](../../README.md#running-locally-with-docker-compose) step).
