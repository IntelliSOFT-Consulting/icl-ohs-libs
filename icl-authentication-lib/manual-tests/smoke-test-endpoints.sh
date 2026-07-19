#!/usr/bin/env bash
# manual-tests/smoke-test-endpoints.sh
#
# Exercises every /auth endpoint against an already-running server. Bring up
# infra first with ./scripts/setup-and-test.sh, or manually:
#   docker compose up -d postgres keycloak && ./gradlew :server:run
# Uses the demo user seeded by realm-export/icl-realm.json (demo.nurse / demo1234).
#
# Unlike setup-and-test.sh this does not stop at the first failure - it runs every
# check and reports a pass/fail summary at the end, so a single broken endpoint
# doesn't hide the state of the rest.
set -uo pipefail
cd "$(dirname "$0")/.."

[ -f .env ] && { set -a; source .env; set +a; }
BASE="http://localhost:${PORT:-8080}"

BODY_FILE=$(mktemp)
trap 'rm -f "$BODY_FILE"' EXIT

PASS=0
FAIL=0

# req METHOD PATH [JSON_BODY] [BEARER_TOKEN] -> prints HTTP status, leaves body in $BODY_FILE
req() {
  local method="$1" path="$2" data="${3:-}" token="${4:-}"
  local args=(-s -o "$BODY_FILE" -w "%{http_code}" -X "$method" "$BASE$path" -H "Content-Type: application/json")
  [ -n "$token" ] && args+=(-H "Authorization: Bearer $token")
  [ -n "$data" ] && args+=(-d "$data")
  curl "${args[@]}"
}

check() {
  local desc="$1" expected="$2" actual="$3"
  if [ "$actual" = "$expected" ]; then
    echo "  PASS  $desc (HTTP $actual)"
    PASS=$((PASS + 1))
  else
    echo "  FAIL  $desc (expected HTTP $expected, got $actual)"
    echo "        body: $(cat "$BODY_FILE")"
    FAIL=$((FAIL + 1))
  fi
}

json_field() { python3 -c "import sys,json; print(json.load(open('$BODY_FILE')).get('$1',''))" 2>/dev/null; }

echo "==> Smoke-testing $BASE"

if ! curl -s -o /dev/null "$BASE/auth/roles"; then
  echo "Cannot reach $BASE - is the server running? See ./scripts/setup-and-test.sh." >&2
  exit 1
fi

echo "-- Login --"
status=$(req POST /auth/login '{"username":"demo.nurse","password":"demo1234"}')
check "login with valid credentials" 200 "$status"
ACCESS_TOKEN=$(json_field access_token)
REFRESH_TOKEN=$(json_field refresh_token)

status=$(req POST /auth/login '{"username":"demo.nurse","password":"wrong"}')
check "login with invalid credentials" 401 "$status"

echo "-- Protected routes --"
status=$(req GET /auth/profile "" "$ACCESS_TOKEN")
check "profile with valid token" 200 "$status"

status=$(req GET /auth/profile)
check "profile with no token" 401 "$status"

status=$(req GET /auth/roles "" "$ACCESS_TOKEN")
check "roles with valid token" 200 "$status"

status=$(req GET /auth/audit "" "$ACCESS_TOKEN")
check "audit as FACILITY_NURSE (expect forbidden)" 403 "$status"

echo "-- Token lifecycle --"
status=$(req POST /auth/introspect "{\"token\":\"$ACCESS_TOKEN\"}")
check "introspect a valid token" 200 "$status"
[ "$(json_field active)" = "True" ] \
  && { echo "  PASS  introspect reports active=true"; PASS=$((PASS + 1)); } \
  || { echo "  FAIL  introspect did not report active=true"; FAIL=$((FAIL + 1)); }

status=$(req POST /auth/introspect '{"token":"not-a-real-token"}')
check "introspect a garbage token" 200 "$status"
[ "$(json_field active)" = "False" ] \
  && { echo "  PASS  introspect reports active=false"; PASS=$((PASS + 1)); } \
  || { echo "  FAIL  introspect did not report active=false"; FAIL=$((FAIL + 1)); }

status=$(req POST /auth/refresh "{\"refresh_token\":\"$REFRESH_TOKEN\"}")
check "refresh with valid refresh token" 200 "$status"
NEW_REFRESH_TOKEN=$(json_field refresh_token)

echo "-- Password reset (stubbed) --"
status=$(req POST /auth/password-reset/request '{"username":"demo.nurse"}')
check "password reset request" 202 "$status"

status=$(req POST /auth/password-reset/confirm '{"username":"demo.nurse","code":"000000","new_password":"newpass1234"}')
check "password reset confirm" 200 "$status"

echo "-- Logout --"
status=$(req POST /auth/logout "{\"refresh_token\":\"$NEW_REFRESH_TOKEN\"}" "$ACCESS_TOKEN")
check "logout" 204 "$status"

echo
echo "==> $PASS passed, $FAIL failed"
[ "$FAIL" -eq 0 ]
