#!/usr/bin/env bash
# HF-26: EXPLAIN (ANALYZE, BUFFERS) of the R1 hot queries in a throwaway Postgres. Usage: ./perf/sql/explain.sh
# Writes go ONLY to the container hf26-explain (--network none, no volume). The live DBs are read (SELECT/SHOW) only.
# The container is removed on exit, success or failure. No other container or volume is touched.
set -euo pipefail
export PATH="/Applications/Docker.app/Contents/Resources/bin:$PATH"
cd "$(git rev-parse --show-toplevel)"

NAME=hf26-explain
# Same image (and so the same binary and stock config) as habitflow-db and habitflow-insights-db: PG 15.15.
PG_IMAGE='sha256:b3968e348b48f1198cc6de6611d055dbad91cd561b7990c406c3fc28d7095b21'
OUT="${OUT:-docs/perf/r1-explain-raw.txt}"
PROM="${PROM:-http://localhost:9090}"
mkdir -p "$(dirname "$OUT")"; : > "$OUT"
log() { printf '%s\n' "$*" | tee -a "$OUT"; }
run() { "$@" 2>&1 | tee -a "$OUT"; return "${PIPESTATUS[0]}"; }
live_habit() { docker compose exec -T postgres psql -U postgres -d habitflow -At -F ' | ' -c "$1"; }
live_ins() { docker compose exec -T insights-db psql -U postgres -d insights -At -F ' | ' -c "$1"; }
tpsql() { docker exec -i "$NAME" psql -U postgres -v ON_ERROR_STOP=1 "$@"; }

# Guard: never reuse or remove a container this script did not create.
if [ -n "$(docker ps -aq --filter "name=^${NAME}$")" ]; then
  echo "A container named $NAME already exists; not touching it. Remove it yourself if it is stale." >&2
  exit 2
fi

live_proof() {
  log "== live proof ($1), DB time $(live_habit "SELECT to_char(now() AT TIME ZONE 'UTC','YYYY-MM-DD HH24:MI:SS')") UTC"
  log "   habitflow: $(live_habit "SELECT 'habits=' || (SELECT count(*) FROM habits) || ' habit_completions=' || (SELECT count(*) FROM habit_completions) || ' outbox_events=' || (SELECT count(*) FROM outbox_events) || ' outbox_unsent=' || (SELECT count(*) FROM outbox_events WHERE sent_at IS NULL)")"
  log "   insights:  $(live_ins "SELECT 'processed_events=' || (SELECT count(*) FROM processed_events) || ' daily_completion_counts=' || (SELECT count(*) FROM daily_completion_counts)")"
  for c in habitflow-db habitflow-insights-db; do
    log "   $(docker inspect -f '{{.Name}} started={{.State.StartedAt}} image={{.Image}}' "$c")"
  done
}

log "== HF-26 EXPLAIN run $(date -u +%Y%m%dT%H%M%SZ) (local $(date '+%Y-%m-%d %H:%M:%S %Z'))"
log "== git: $(git rev-parse HEAD) on $(git rev-parse --abbrev-ref HEAD)"
git status --porcelain | sed 's/^/   git status: /' | tee -a "$OUT"
log "== host: $(sysctl -n hw.model), $(sysctl -n machdep.cpu.brand_string), $(sysctl -n hw.ncpu) CPUs, $(( $(sysctl -n hw.memsize) / 1073741824 )) GiB RAM"
log "== docker: $(docker info --format 'server {{.ServerVersion}}, VM {{.NCPU}} CPUs, {{.MemTotal}} bytes memory')"
log "== running containers (incl. non-habitflow ones sharing the VM):"
docker ps --format '{{.Names}}  {{.Image}}  {{.Status}}' | sed 's/^/   /' | tee -a "$OUT"
live_proof before

log "== live habitflow-db settings (read only):"
run live_habit "SELECT name || ' = ' || setting || coalesce(' ' || unit, '') FROM pg_settings WHERE name IN ('server_version','shared_buffers','work_mem','effective_cache_size','random_page_cost','jit','plan_cache_mode') ORDER BY name" >/dev/null

log "== start $NAME (image $PG_IMAGE, --network none, no published port, no volume)"
trap 'docker rm -f "$NAME" >/dev/null 2>&1 && echo "== removed $NAME" | tee -a "$OUT" || true' EXIT
docker run -d --name "$NAME" --network none -e POSTGRES_PASSWORD=postgres \
  -v "$PWD/services/habit-service/src/main/resources/db/migration:/mig/habit:ro" \
  -v "$PWD/services/insights-service/src/main/resources/db/migration:/mig/insights:ro" \
  -v "$PWD/perf/sql:/hf26:ro" \
  "$PG_IMAGE" >/dev/null

# The entrypoint runs a temporary server during init, so wait for init to finish, then for pg_isready.
for _ in $(seq 1 60); do
  docker logs "$NAME" 2>&1 | grep -q 'PostgreSQL init process complete' && docker exec "$NAME" pg_isready -U postgres -q && break
  sleep 1
done
docker exec "$NAME" pg_isready -U postgres -q || { log "$NAME did not become ready in 60 s"; exit 3; }
log "== $NAME ready"

log "== throwaway settings:"
run tpsql -At -c "SELECT name || ' = ' || setting || coalesce(' ' || unit, '') FROM pg_settings WHERE name IN ('server_version','shared_buffers','work_mem','effective_cache_size','random_page_cost','jit','plan_cache_mode') ORDER BY name" >/dev/null

log "== schema: real migration files via psql -f, in version order (no Flyway history table)"
run tpsql -q -c 'CREATE DATABASE habitflow' -c 'CREATE DATABASE insights' >/dev/null
run tpsql -q -d habitflow -f /mig/habit/V1__baseline.sql >/dev/null
run tpsql -q -d habitflow -f /mig/habit/V2__outbox.sql >/dev/null
run tpsql -q -d insights -f /mig/insights/V1__projection.sql >/dev/null
run tpsql -At -d habitflow -c "SELECT '   ' || tablename || ': ' || indexdef FROM pg_indexes WHERE schemaname = 'public' ORDER BY tablename, indexname" >/dev/null
run tpsql -At -d insights -c "SELECT '   ' || tablename || ': ' || indexdef FROM pg_indexes WHERE schemaname = 'public' ORDER BY tablename, indexname" >/dev/null

log "== seed"
run tpsql -d habitflow -f /hf26/seed-habit.sql >/dev/null
run tpsql -d insights -f /hf26/seed-insights.sql >/dev/null
log "== VACUUM (ANALYZE)"
run tpsql -q -d habitflow -c 'VACUUM (ANALYZE)' >/dev/null
run tpsql -q -d insights -c 'VACUUM (ANALYZE)' >/dev/null

SIZES="SELECT '   ' || c.relname || ': rows=' || (xpath('//n/text()', query_to_xml('SELECT count(*) AS n FROM ' || c.relname, false, false, '')))[1]::text
        || ' relpages=' || c.relpages || ' heap=' || pg_size_pretty(pg_relation_size(c.oid))
        || ' indexes=' || pg_size_pretty(pg_indexes_size(c.oid))
       FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
       WHERE n.nspname = 'public' AND c.relkind = 'r' ORDER BY c.relname"
log "== seeded sizes, habitflow:"; run tpsql -At -d habitflow -c "$SIZES" >/dev/null
log "   outbox_events unsent=$(tpsql -At -d habitflow -c 'SELECT count(*) FROM outbox_events WHERE sent_at IS NULL')"
log "   outbox payload avg bytes=$(tpsql -At -d habitflow -c 'SELECT round(avg(pg_column_size(payload))) FROM outbox_events')"
log "== seeded sizes, insights:"; run tpsql -At -d insights -c "$SIZES" >/dev/null

log "== EXPLAIN, habitflow (Q1 claim, Q3 findByUserId, Q4 exists)"
run tpsql -d habitflow -f /hf26/explain-habit.sql >/dev/null
log "== EXPLAIN, insights (Q2 processed_events insert + projection upsert)"
run tpsql -d insights -f /hf26/explain-insights.sql >/dev/null

# Server-side means during the HF-25 k6 load window (run 20261122T171517Z; load 17:15:22Z to about 17:17:30Z).
# Read only. Means only: these timers have no histogram buckets, and the _max gauges are rolling windows (not used).
log "== Prometheus ($PROM), evaluated at 2026-11-22T17:17:30Z over [2m] (HF-25 load window)"
prom() {
  log "   PromQL: $1"
  log "   -> $(curl -fsS -G "$PROM/api/v1/query" --data-urlencode "query=$1" --data-urlencode 'time=2026-11-22T17:17:30Z' || echo 'UNAVAILABLE')"
}
SEL='uri=~"/api/auth/validate|/api/habits|/api/habits/{id}/toggle"'
prom "sum by (job,method,uri,status) (increase(http_server_requests_seconds_sum{$SEL}[2m])) / sum by (job,method,uri,status) (increase(http_server_requests_seconds_count{$SEL}[2m]))"
prom "sum by (job,method,uri,status) (increase(http_server_requests_seconds_count{$SEL}[2m]))"

live_proof after
log "== done (the EXIT trap removes $NAME next)"
