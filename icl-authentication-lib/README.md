# icl-authentication-lib

A Kotlin Multiplatform authentication library built on **Ktor**. Two Gradle subprojects:

```
icl-authentication-lib/
├── core/            KMP library: DTOs, KeycloakHttpClient, policy logic (commonMain)
│                    + Exposed/Postgres, JWT verification (jvmMain)
├── server/          Plain JVM Ktor application that runs :core as a service
├── docker-compose.yml
├── .env.sample
├── realm-export/    Template that seeds Keycloak with a demo realm + user for local testing
├── scripts/setup-and-test.sh
├── manual-tests/    Postman collection + smoke-test script for a running instance (see its README-TEST.md)
├── LICENSE          Apache 2.0
└── Dockerfile
```

## Why split into `core` + `server`

`core`'s `commonMain` source set has **zero JVM-only dependencies** — DTOs
(`kotlinx.serialization`), `KeycloakHttpClient` (pure Ktor Client), and the role/lockout
policy logic are all genuinely multiplatform today. That's the reusable part: drop an
`androidTarget()` / `iosArm64()` block into `core/build.gradle.kts` later and a mobile
client SDK can depend on `core` directly for login/refresh calls and token-claim models,
without pulling in Postgres or a JWT-verification library it doesn't need.

`server` is a deliberately JVM-only Ktor application — the thing that actually terminates
HTTP, verifies JWTs against Keycloak's JWKS endpoint, and talks to Postgres. That will
never run on a phone, KMP or not, and pretending otherwise would just add complexity.

## What's shared vs. JVM-only today

| Package | Source set | Notes |
|---|---|---|
| `model/*` (DTOs) | `core/commonMain` | `kotlinx.serialization`, no framework coupling |
| `client/KeycloakHttpClient` | `core/commonMain` | Pure Ktor Client — reusable by a mobile SDK verbatim |
| `policy/RolePolicy`, `policy/LockoutPolicy` | `core/commonMain` | Pure functions, unit-tested in `commonTest` with no infra |
| `persistence/*` (Exposed tables) | `core/jvmMain` | JDBC-backed, JVM-only |
| `audit/AuditLogService`, `audit/LoginAttemptService` | `core/jvmMain` | Use Exposed transactions |
| `security/JwtVerifier` | `core/jvmMain` | Wraps `com.auth0:jwks-rsa`, JVM-only |
| Ktor routing (`AuthModule.kt`), `Main.kt` | `server/` | The actual running service |

## Endpoints

| Endpoint | Method | Purpose |
|---|---|---|
| `/auth/login` | POST | Credentials → access + refresh tokens |
| `/auth/refresh` | POST | Rotate refresh token |
| `/auth/introspect` | POST | Validate a token, return claims (service-to-service) |
| `/auth/profile` | GET | Authenticated user's identity + roles |
| `/auth/roles` | GET | Roles configured for this realm |
| `/auth/logout` | POST | Revoke current session |
| `/auth/audit` | GET | `MOH_ADMIN`/`SUPER_ADMIN`-only paginated audit trail |
| `/auth/password-reset/request`, `/confirm` | POST | Self-service password reset |

## Local setup

```bash
cp .env.sample .env                 # fill in real values (defaults work for local dev as-is)
./scripts/setup-and-test.sh         # starts Postgres + Keycloak, runs tests, smoke-tests /auth/login
```

`realm-export/icl-realm.json.template` seeds Keycloak with the `icl-realm` realm, the
`icl-backend` client, three roles, and a demo user (`demo.nurse` / `demo1234`) — so the
smoke test has something real to log in against without any manual Keycloak console work.

The `keycloak` service in `docker-compose.yml` renders this template into
`realm-export/icl-realm.json` on every container start, substituting
`KEYCLOAK_ACCESS_TOKEN_LIFESPAN_SECONDS` and `KEYCLOAK_REFRESH_TOKEN_LIFESPAN_SECONDS`
from `.env` (defaults: 300s / 1800s) into the realm's `accessTokenLifespan` and
`ssoSessionIdleTimeout` fields — these are what set `expires_in` and `refresh_expires_in`
in the `/auth/login` and `/auth/refresh` responses. The generated `icl-realm.json` isn't
committed (it's build output); only the `.template` is. Changing the values in `.env`
only takes effect on a realm that doesn't exist yet — Keycloak has no persistent volume
here (dev mode storage lives in the container), so if it already imported the realm once,
recreate the container to force a fresh import:
`docker compose rm -f -s keycloak && docker compose up -d keycloak`. Alternatively, update
the realm's token lifespans by hand in the Keycloak admin console.

## Manual run

```bash
docker compose up -d postgres keycloak
./gradlew :server:run
curl -X POST http://localhost:8080/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"demo.nurse","password":"demo1234"}'
```

## Testing

```bash
./gradlew :core:jvmTest        # commonTest (policy logic) + jvmTest (Testcontainers Postgres)
./gradlew :server:test         # Ktor testApplication route tests
```

To exercise every endpoint against a running instance (login, refresh, introspect,
profile, roles, audit, logout, password reset) via a CLI script or a Postman collection,
see [`manual-tests/README-TEST.md`](manual-tests/README-TEST.md).

## Build & run in Docker

```bash
docker compose up -d --build
```

## Publishing to Maven Central

Only `core` is published — it's the reusable library (DTOs, `KeycloakHttpClient`, policy
logic); `server` is a runnable application, not something other projects depend on.
Publishing is handled by the [`com.vanniktech.maven.publish`](https://github.com/vanniktech/gradle-maven-publish-plugin)
plugin, configured in `core/build.gradle.kts`, targeting Maven Central's current
[Central Portal](https://central.sonatype.org/) (the old OSSRH/`oss.sonatype.org` flow was
shut down in June 2025).

### One-time account setup

1. Create an account at [central.sonatype.com](https://central.sonatype.com/) and verify
   ownership of the `ke.intellisoft.icl` namespace (via a DNS TXT record on a domain you
   control, or a verified GitHub org/user matching the reversed domain). This step can
   take a while the first time — do it well before you need to publish.
2. Generate a **user token** from your Central Portal account settings (this is not your
   login password — it's a separate token pair used for publishing).
3. Generate a GPG key pair if you don't already have one, and publish the **public** key
   to a keyserver Central checks against, e.g.:
   ```bash
   gpg --full-generate-key
   gpg --keyserver keyserver.ubuntu.com --send-keys <YOUR_KEY_ID>
   ```
   Export the **private** key in ASCII-armored form for Gradle to use:
   ```bash
   gpg --export-secret-keys --armor <YOUR_KEY_ID> > private-key.asc
   ```

### Fill in the placeholders

`core/build.gradle.kts`'s `mavenPublishing { pom { ... } }` block has three `REPLACE_ME`
placeholders (project URL, developer URL, SCM URLs) — there's no public repo for this
project yet, so these are stubbed in. Update them once a real repo exists; Central
validates that these are well-formed but not that they're reachable, so publishing won't
literally fail without doing this, but the metadata will be wrong.

### Credentials — env vars only, never in `build.gradle.kts` or committed files

Gradle auto-maps `ORG_GRADLE_PROJECT_<name>` environment variables to project properties,
which is how the plugin picks up credentials without them ever touching a file:

```bash
export ORG_GRADLE_PROJECT_mavenCentralUsername="<central portal token username>"
export ORG_GRADLE_PROJECT_mavenCentralPassword="<central portal token password>"
export ORG_GRADLE_PROJECT_signingInMemoryKey="$(cat private-key.asc)"
export ORG_GRADLE_PROJECT_signingInMemoryKeyPassword="<gpg key passphrase>"
```

(Or put the equivalent `mavenCentralUsername=...` etc. lines in `~/.gradle/gradle.properties`,
which is outside the repo and never committed — same idea, different mechanism.)

### Publish

```bash
# Dry run - installs into ~/.m2 with no credentials/signing needed, good for sanity-checking
# the POM and artifact layout before touching the real thing:
./gradlew :core:publishToMavenLocal

# The real thing, once credentials are set and the version in core/build.gradle.kts
# is no longer a -SNAPSHOT (Central rejects SNAPSHOT releases):
./gradlew :core:publishToMavenCentral
```

`./gradlew :core:tasks --group publishing` lists every generated publish/POM/signing task
if you need to target one specifically (e.g. just the `jvm` publication vs. the full KMP
`kotlinMultiplatform` one).

## Notes / scope of this implementation

- `/auth/password-reset/*` and `/auth/password` are stubbed the same way — wire the
  Keycloak Admin REST API (via a second `KeycloakHttpClient`-style class using the
  client-credentials grant) to actually update the password.
- `/auth/audit` returns an empty list — the query against the `AuditLog` Exposed table
  (paginated, filtered by `userId`) is a few lines away using the same `transaction {}`
  pattern as `AuditLogService`.
- `androidTarget()` / `iosArm64()` are commented out in `core/build.gradle.kts` rather
  than removed — enabling them is the entire "make this a mobile client SDK" step once
  that's wanted.
