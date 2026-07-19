# README-TEST

Manual/live testing tools for `icl-authentication-lib`, kept separate from the codebase
itself. This folder covers exercising a **running** instance's `/auth/*` endpoints — it's
not where the Gradle unit/integration tests live (those are `core/src/commonTest`,
`core/src/jvmTest`, `server/src/test`, run via `./gradlew :core:jvmTest :server:test`).

## Contents

| File | What it's for |
|---|---|
| `smoke-test-endpoints.sh` | Single-command CLI sweep of every endpoint. No dependencies beyond `curl` and `python3`. CI-friendly: exits non-zero on any failure. |
| `icl-authentication-lib.postman_collection.json` | The same coverage, importable into Postman for interactive/manual testing, or runnable headlessly via Newman. |

## Prerequisite: a running instance

Both tools assume the server is already up. From the repo root:

```bash
cp .env.sample .env                 # first time only
docker compose up -d postgres keycloak
./gradlew :server:run &
```

Or use `../scripts/setup-and-test.sh`, which brings up infra, runs the Gradle test suite,
and starts the server for you.

The server listens on `PORT` from `.env` (default `8080`).

## Option A: smoke-test-endpoints.sh

```bash
./manual-tests/smoke-test-endpoints.sh
```

Reads `.env` from the repo root for `PORT`. Prints a `PASS`/`FAIL` line per check plus the
response body on failure, and a final `N passed, M failed` summary. Exits `0` only if
everything passed — safe to drop into CI.

## Option B: Postman collection

Import `icl-authentication-lib.postman_collection.json` into Postman. Set the collection's
`base_url` variable to match your server (default in the collection is
`http://localhost:8080` — change it if your `.env` overrides `PORT`).

Run requests in order, top to bottom: **Login → Protected routes → Token lifecycle →
Password reset → Logout**. The Login and Refresh requests' test scripts automatically
write `access_token`/`refresh_token` into collection variables, so downstream requests
pick them up without manual copy-pasting.

To run headlessly instead of via the Postman UI:

```bash
npx newman run manual-tests/icl-authentication-lib.postman_collection.json \
  --env-var base_url=http://localhost:8080
```

## What "success" looks like

Both tools check the same 12 requests / ~14 assertions:

- Login with valid credentials → `200` with an access + refresh token
- Login with invalid credentials → `401 invalid_credentials`
- Profile/Roles with a valid token → `200`
- Profile with no token → `401`
- **Audit as the seeded `demo.nurse` user → `403 insufficient_role`** — this is expected,
  not a failure. `demo.nurse` only has `FACILITY_NURSE`; a `200` here would need a user
  with `MOH_ADMIN` or `SUPER_ADMIN` (not seeded by default — see
  `../realm-export/icl-realm.json.template` to add one).
- Introspect a valid token → `active: true`; a garbage token → `active: false`
- Refresh → `200` with a new token pair
- Password reset request/confirm → `202` / `200` — these routes are **stubbed** (see
  `../CLAUDE.md`'s "Known-incomplete pieces"), so a passing check only confirms the route
  responds with the right shape, not that a password was actually changed.
- Logout → `204`

## Token lifetimes affect repeat runs

Access tokens expire after `KEYCLOAK_ACCESS_TOKEN_LIFESPAN_SECONDS` (default 300s/5min),
refresh tokens after `KEYCLOAK_REFRESH_TOKEN_LIFESPAN_SECONDS` (default 1800s/30min) — both
configured in `.env`. If you're re-running these tools well after a first pass, tokens from
a prior run may have expired; both tools always start from a fresh `Login` call, so this
only matters if you're manually replaying individual Postman requests out of order.
