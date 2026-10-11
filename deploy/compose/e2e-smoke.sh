#!/usr/bin/env bash
# E2E smoke test — FR-CICD-005, local-environment.md §8, IMP-M0-11.
#
# Brings up the core compose profile, waits for both services' health endpoints, tears the
# stack down. This is the honest ceiling for FR-CICD-005 today: there is no feature to
# exercise end-to-end yet (M1 accounts have not landed), so a health-endpoint round trip is
# what "the deployed stack answers over real HTTP" actually checks right now. Reports
# pass/fail for information only — it gates nothing (FR-CICD-003, OQ-002).
#
# gamehost and frontend have no `build:` block in docker-compose.yml (this repo stays
# independently usable without the root, ADR-0012) — their images are built here from sibling
# checkouts of turnbase-game-host and turnbase-frontend, the same repo layout
# local-environment.md §4 assumes.
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")"

GAME_HOST_DIR="${TURNBASE_GAME_HOST_DIR:-../../../turnbase-game-host}"
FRONTEND_DIR="${TURNBASE_FRONTEND_DIR:-../../../turnbase-frontend}"

cleanup() {
  docker compose down -v
  rm -f .env
}
trap cleanup EXIT

cat >.env <<ENV
TURNBASE_DB_OWNER_PASSWORD=e2e-owner-pw
TURNBASE_DB_APP_PASSWORD=e2e-app-pw
TURNBASE_REDIS_PASSWORD=e2e-redis-pw
MINIO_ROOT_USER=e2e-minio
MINIO_ROOT_PASSWORD=e2e-minio-pw
TURNBASE_ARTIFACT_STORE_ACCESS_KEY=e2e-app
TURNBASE_ARTIFACT_STORE_SECRET_KEY=e2e-app-secret
TURNBASE_ADMIN_USERNAME=e2e-admin
TURNBASE_ADMIN_PASSWORD=e2e-admin-pw
ENV

echo "Building turnbase/game-host:dev from ${GAME_HOST_DIR}..."
docker build -t turnbase/game-host:dev "${GAME_HOST_DIR}"
echo "Building turnbase/frontend:dev from ${FRONTEND_DIR}..."
docker build -t turnbase/frontend:dev "${FRONTEND_DIR}"

echo "Bringing up the core compose profile..."
# On failure, the cleanup trap's `docker compose down -v` discards every container before
# anyone can see why one of them exited — print each service's log here first, so a startup
# crash is diagnosable from the CI log alone rather than requiring a local reproduction.
if ! docker compose up -d --build --wait --wait-timeout 180; then
  echo "compose up --wait failed — service logs follow:" >&2
  docker compose logs --no-color >&2
  exit 1
fi

echo "Platform health:"
# Actuator lives on the management port (8081), not the app port (8080) — IMP-M0-03. It's
# published loopback-only, which this host reaches directly (IMP-GATE-14: the restriction is
# the published port's host-side bind, not the container's own listener address).
curl -sf http://127.0.0.1:8081/actuator/health

echo
echo "Game Host health:"
curl -sf http://127.0.0.1:8082/actuator/health

echo
echo "Game Host registration (IMP-GATE-08, local-environment.md §3: <= 60 s):"
# §3's budget runs from monolith readiness, so it starts here: `up --wait` returned, which
# means the monolith's readiness probe passed (the Game Host waits on it). Starting earlier
# would count image builds and the monolith's own boot, which a cold CI runner spends
# minutes on. The registry's `hosts` set is what the Orchestration client selects from, so a
# member there is the acknowledged Register, not merely a log line.
UP_STARTED=$(date +%s)
registered=0
while [ $(( $(date +%s) - UP_STARTED )) -le 60 ]; do
  members=$(docker compose exec -T redis sh -c \
    'REDISCLI_AUTH="$TURNBASE_REDIS_PASSWORD" redis-cli scard hosts' 2>/dev/null | tr -d '\r' || true)
  if [ "${members:-0}" -ge 1 ] 2>/dev/null; then registered=1; break; fi
  sleep 1
done
if [ "$registered" != 1 ]; then
  echo "No Game Host registered in Redis within 60 s of the monolith becoming ready." >&2
  docker compose logs --no-color gamehost platform >&2
  exit 1
fi
echo "registered $(( $(date +%s) - UP_STARTED )) s after monolith readiness"

echo
echo "Frontend shell and proxy (the platform origin is the Frontend container, IMP-GATE-74):"
# The shell document, served for the platform host with its CSP...
curl -sf -H "Host: turnbase.localhost:8080" http://127.0.0.1:8080/ | grep -q '<div id="root"'
curl -sfI -H "Host: turnbase.localhost:8080" http://127.0.0.1:8080/ | grep -qi '^content-security-policy:'
# ...and /api proxied through to the monolith (unauthenticated read, so no session needed).
curl -sf -H "Host: turnbase.localhost:8080" http://127.0.0.1:8080/api/auth/providers

echo
echo "E2E smoke test passed."
