#!/usr/bin/env bash
# E2E smoke test — FR-CICD-005, local-environment.md §8, IMP-M0-11.
#
# Brings up the core compose profile, waits for both services' health endpoints, tears the
# stack down. This is the honest ceiling for FR-CICD-005 today: there is no feature to
# exercise end-to-end yet (M1 accounts have not landed), so a health-endpoint round trip is
# what "the deployed stack answers over real HTTP" actually checks right now. Reports
# pass/fail for information only — it gates nothing (FR-CICD-003, OQ-002).
#
# gamehost has no `build:` block in docker-compose.yml (this repo stays independently usable
# without the root, ADR-0012) — its image is built here from a sibling checkout of
# turnbase-game-host, the same repo layout local-environment.md §4 assumes.
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")"

GAME_HOST_DIR="${TURNBASE_GAME_HOST_DIR:-../../../turnbase-game-host}"

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
TURNBASE_ADMIN_USERNAME=e2e-admin
TURNBASE_ADMIN_PASSWORD=e2e-admin-pw
ENV

echo "Building turnbase/game-host:dev from ${GAME_HOST_DIR}..."
docker build -t turnbase/game-host:dev "${GAME_HOST_DIR}"

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
echo "E2E smoke test passed."
