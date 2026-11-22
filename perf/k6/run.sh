#!/usr/bin/env bash
# HF-25 (R1-17a): one command for the k6 baseline of the toggle and list paths.
#
#   ./perf/k6/run.sh                                   # baseline -> docs/perf/r1-k6-before.txt
#   OUT=docs/perf/r1-k6-after.txt ./perf/k6/run.sh     # HF-27's rerun, same script and image
#
# Needs the compose stack up (docker compose up -d). k6 runs from the pinned grafana/k6 image on the
# compose network; it is not a project dependency. Everything (environment, k6 summary, outbox lag
# samples, drain check) is teed raw into $OUT. The exit code is k6's own (99 = a threshold missed);
# if k6 passed but the outbox didn't drain within 60 s, it is 1.
#
# Optional env: NOTES_FILE (text copied into the header, e.g. what was stopped for the run);
# LOAD_VUS / LOAD_DURATION (dry runs only; the baseline is 50 VUs for 2m).
set -euo pipefail
export PATH="/Applications/Docker.app/Contents/Resources/bin:$PATH"
cd "$(git rev-parse --show-toplevel)"

K6_IMAGE='grafana/k6:2.3.0@sha256:2dd4756ec6e14e62778916e1eb850108e4b499304c02df56760fa0bdb0fba0b8'
OUT="${OUT:-docs/perf/r1-k6-before.txt}"
RUN_ID="$(date -u +%Y%m%dT%H%M%SZ)"
mkdir -p "$(dirname "$OUT")"
: > "$OUT"

log() { printf '%s\n' "$*" | tee -a "$OUT"; }
# The compose postgres container (habitflow-db), never localhost:5432 (that can be a host Postgres).
psql_db() { docker compose exec -T postgres psql -U postgres -d habitflow -At -F ' | ' -c "$1" </dev/null; }
stats() { docker stats --no-stream --format '{{.Name}} cpu={{.CPUPerc}} mem={{.MemUsage}}' | sed 's/^/   /' | tee -a "$OUT"; }

# Outbox lag, computed in DB time only: db_time (UTC) | unsent rows | oldest unsent age (s)
LAG_SQL="SELECT to_char(now() AT TIME ZONE 'UTC', 'HH24:MI:SS'), count(*),
         coalesce(round(extract(epoch FROM now() - min(created_at))::numeric, 2), 0)
         FROM outbox_events WHERE sent_at IS NULL"

log "== HF-25 k6 run $RUN_ID (local $(date '+%Y-%m-%d %H:%M:%S %Z'))"
log "== vus=${LOAD_VUS:-50} duration=${LOAD_DURATION:-2m}  mix: 1 toggle + 1 list per iteration, 1 s think time"
log "== git: $(git rev-parse HEAD) on $(git rev-parse --abbrev-ref HEAD)"
git status --porcelain | sed 's/^/   git status: /' | tee -a "$OUT"
log "== host: $(sysctl -n hw.model), $(sysctl -n machdep.cpu.brand_string), $(sysctl -n hw.ncpu) CPUs, $(( $(sysctl -n hw.memsize) / 1073741824 )) GiB RAM"
log "== docker: $(docker info --format 'server {{.ServerVersion}}, {{.OperatingSystem}}, VM {{.NCPU}} CPUs, {{.MemTotal}} bytes memory')"
log "== $(docker compose version)"
log "== k6 image: $K6_IMAGE"
if [ -n "${NOTES_FILE:-}" ]; then
  log "== operator notes:"
  sed 's/^/   /' "$NOTES_FILE" | tee -a "$OUT"
fi
log "== compose services (container | image id | image built | container started):"
for c in habitflow-auth habitflow-habits habitflow-insights habitflow-db habitflow-insights-db habitflow-kafka habitflow-zookeeper habitflow-prometheus; do
  img=$(docker inspect -f '{{.Image}}' "$c")
  log "   $c | $(docker image inspect -f '{{.Id}} | {{.Created}}' "$img") | $(docker inspect -f '{{.State.StartedAt}}' "$c")"
done
log "== all running containers (this VM):"
docker ps --format '{{.Names}}  {{.Image}}  {{.Status}}' | sed 's/^/   /' | tee -a "$OUT"
log "== docker stats before:"; stats

NET=$(docker inspect habitflow-habits --format '{{range $k, $v := .NetworkSettings.Networks}}{{$k}}{{end}}')
[ -n "$NET" ] || { log "habitflow-habits has no network: is the compose stack up?"; exit 2; }
log "== network: $NET"
log "== outbox before (db_time | unsent | oldest_unsent_age_s): $(psql_db "$LAG_SQL")"
OUTBOX_MARK=$(psql_db "SELECT now()") # DB time; outbox rows created after this belong to this run

# Background lag sampler every 5 s (DB time only); a failed sample is logged, not fatal.
( while :; do printf '   lag %s\n' "$(psql_db "$LAG_SQL" 2>&1 || echo 'sample failed')"; sleep 5; done ) >> "$OUT" 2>&1 &
SAMPLER=$!
trap 'kill "$SAMPLER" 2>/dev/null || true' EXIT

log "== k6 start (lag samples: db_time | unsent | oldest_unsent_age_s)"
set +e
docker run --rm --network "$NET" -v "$PWD/perf/k6:/scripts:ro" \
  -e RUN_ID="$RUN_ID" -e LOAD_VUS="${LOAD_VUS:-50}" -e LOAD_DURATION="${LOAD_DURATION:-2m}" \
  -e AUTH_URL=http://auth-service:8081 -e HABIT_URL=http://habit-service:8080 \
  "$K6_IMAGE" run --quiet /scripts/toggle.js 2>&1 | tee -a "$OUT"
K6_EXIT=${PIPESTATUS[0]}
set -e
kill "$SAMPLER" 2>/dev/null || true
wait "$SAMPLER" 2>/dev/null || true
log "== k6 exit code: $K6_EXIT (0 = all thresholds passed, 99 = a threshold missed, other = error)"

# Drain check: up to 60 s for unsent = 0.
DRAIN_EXIT=1
for _ in $(seq 1 12); do
  row=$(psql_db "$LAG_SQL")
  log "   drain $row"
  if [ "$(echo "$row" | cut -d'|' -f2 | tr -d ' ')" = "0" ]; then DRAIN_EXIT=0; break; fi
  sleep 5
done
if [ "$DRAIN_EXIT" -eq 0 ]; then log "== outbox drained: yes (0 unsent)"; else log "== outbox drained: NO, unsent rows remain after 60 s"; fi

log "== this run's outbox rows, created after $OUTBOX_MARK (DB time, sent_at - created_at):"
log "   rows | unsent | p50_ms | p95_ms | max_ms"
psql_db "SELECT count(*), count(*) FILTER (WHERE sent_at IS NULL),
         round(1000 * extract(epoch FROM percentile_cont(0.5) WITHIN GROUP (ORDER BY sent_at - created_at))::numeric),
         round(1000 * extract(epoch FROM percentile_cont(0.95) WITHIN GROUP (ORDER BY sent_at - created_at))::numeric),
         round(1000 * extract(epoch FROM max(sent_at - created_at))::numeric)
         FROM outbox_events WHERE created_at >= '$OUTBOX_MARK'" | sed 's/^/   /' | tee -a "$OUT"
log "== docker stats after:"; stats

FINAL=$K6_EXIT
[ "$FINAL" -eq 0 ] && FINAL=$DRAIN_EXIT
log "== script exit code: $FINAL"
exit "$FINAL"
