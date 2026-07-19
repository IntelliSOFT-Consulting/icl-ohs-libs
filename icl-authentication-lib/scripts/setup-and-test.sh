#!/usr/bin/env bash
# scripts/setup-and-test.sh
#
# Brings up Keycloak + Postgres, waits for both to be healthy, then runs the auth
# server's own test suite plus a live smoke test against /auth/login using the demo
# user seeded by realm-export/icl-realm.json (demo.nurse / demo1234).
set -euo pipefail
cd "$(dirname "$0")/.."

if [ ! -f .env ]; then
  echo "Missing .env - copy .env.sample to .env first." >&2
  exit 1
fi
set -a; source .env; set +a

echo "==> Starting Postgres + Keycloak..."
docker compose up -d postgres keycloak

echo "==> Waiting for Postgres..."
until docker compose exec -T postgres pg_isready -U "${DB_USERNAME}" > /dev/null 2>&1; do sleep 1; done

echo "==> Waiting for Keycloak on :${KEYCLOAK_PORT}..."
until curl -sf "http://localhost:${KEYCLOAK_PORT}/realms/${KEYCLOAK_REALM}" > /dev/null 2>&1; do sleep 2; done
echo "Keycloak is up."

echo "==> Running unit + integration tests (Testcontainers spins up its own Postgres)..."
./gradlew :core:jvmTest :server:test

echo "==> Starting the auth server locally for a live smoke test..."
./gradlew :server:run &
SERVER_PID=$!
trap 'kill $SERVER_PID 2>/dev/null || true' EXIT

echo "==> Waiting for the server on :${PORT:-8080}..."
# /auth/roles requires a bearer token (always 401 without one) - curl without -f still
# exits 0 once it gets any HTTP response, which is all we need to know the port is up.
until curl -s "http://localhost:${PORT:-8080}/auth/roles" > /dev/null 2>&1; do sleep 2; done

echo "==> POST /auth/login with the seeded demo user..."
RESPONSE=$(curl -s -X POST "http://localhost:${PORT:-8080}/auth/login" \
  -H "Content-Type: application/json" \
  -d '{"username":"demo.nurse","password":"demo1234"}')

echo "$RESPONSE" | grep -q access_token && echo "✔ Login succeeded:" || { echo "✘ Login failed:"; echo "$RESPONSE"; exit 1; }
echo "$RESPONSE"

echo "==> Done. Server left running (PID $SERVER_PID) - Ctrl+C to stop, or 'kill $SERVER_PID'."
wait $SERVER_PID
