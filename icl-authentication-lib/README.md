# icl-authentication-lib

A Kotlin Multiplatform authentication library built on **Ktor**, fronting a project's own
Keycloak realm. It can be used two ways:

- **As an embedded library** — a host JVM app (e.g. Spring Boot) imports `core`,
  instantiates `IclOHSAuth`, and calls it directly (`auth.login(...)`, `auth.refresh(...)`,
  etc.) with no HTTP hop. This is the primary, published path.
- **As a standalone HTTP service** — `server/` runs `core` behind a Ktor/Netty process
  exposing `/auth/*` routes, for consumers that would rather call it over the network.

Two Gradle subprojects:

```
icl-authentication-lib/
├── core/            KMP library: DTOs, KeycloakHttpClient, policy logic (commonMain)
│                    + IclOHSAuth facade, Exposed/Postgres, JWT verification (jvmMain)
├── server/          Plain JVM Ktor application - a thin HTTP wrapper over IclOHSAuth
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

`core/jvmMain` adds everything that's inherently JVM-bound (Postgres via Exposed, JWT
signature verification, Flyway) plus `IclOHSAuth` — the facade that orchestrates all of
it and is the class a host JVM app actually calls. It's JVM-only itself (it runs
migrations against a JDBC `DataSource`), but it's still part of `core`, not `server`, so
it's usable without running any HTTP service at all.

`server` is a deliberately JVM-only Ktor application on top of `core` — the thing that
terminates HTTP and maps `IclOHSAuth`'s calls/exceptions to routes/status codes for
consumers that want a network API instead of an in-process dependency. That will never
run on a phone, KMP or not, and pretending otherwise would just add complexity.

## What's shared vs. JVM-only today

| Package | Source set | Notes |
|---|---|---|
| `model/*` (DTOs) | `core/commonMain` | `kotlinx.serialization`, no framework coupling |
| `client/KeycloakHttpClient` | `core/commonMain` | Pure Ktor Client — reusable by a mobile SDK verbatim |
| `policy/RolePolicy`, `policy/LockoutPolicy`, `policy/AudiencePolicy` | `core/commonMain` | Pure functions, unit-tested in `commonTest` with no infra |
| `persistence/*` (Exposed tables + `DatabaseFactory`) | `core/jvmMain` | JDBC-backed, JVM-only. Flyway migrations ship as resources here too (`db/migration/`) |
| `audit/AuditLogService`, `audit/LoginAttemptService` | `core/jvmMain` | Use Exposed transactions |
| `security/JwtVerifier` | `core/jvmMain` | Wraps `com.auth0:jwks-rsa`/`java-jwt`, JVM-only. `verify()` does full signature+issuer checking; `decodeUnverified()` is claims-only |
| `IclOHSAuth`, `IclOHSAuthExceptions` | `core/jvmMain` | The library's public facade — see below |
| Ktor routing (`AuthModule.kt`), `Main.kt` | `server/` | Thin HTTP adapter that builds one `IclOHSAuth` and delegates every route to it |

## Using the library directly (no HTTP)

Add `core` (published as `auth-core`) as a dependency, then construct one `IclOHSAuth`
per process — it owns its own Keycloak calls and its own Postgres connection, running
this library's Flyway migrations against whatever `DataSource` you give it:

```kotlin
val config = AuthConfig.fromEnv(System.getenv())   // or build one directly - see AuthConfig.kt
val dataSource = DatabaseFactory.dataSource(
    host = "localhost", port = 5432, dbName = "myapp", username = "myapp", password = "..."
)
val auth = IclOHSAuth(config, dataSource)          // migrates ohs_auth schema, connects Exposed

val tokens = auth.login("dave", "password")         // -> TokenResponse
val refreshed = auth.refresh(tokens.refreshToken)    // -> TokenResponse
auth.logout(tokens.refreshToken)
val status = auth.introspect(someToken)              // -> IntrospectResponse, never throws
val me = auth.profile(tokens.accessToken)            // -> UserProfile
val sessions = auth.sessions(tokens.accessToken)      // -> List<SessionDto>
val trail = auth.auditLog(tokens.accessToken)         // -> List<AuditLogEntryDto>, MOH_ADMIN/SUPER_ADMIN only
```

In a Spring Boot app, wire it up as a bean once (using your own `DataSource`/Keycloak
config) and inject it wherever you need auth:

```kotlin
@Bean
fun iclOHSAuth(): IclOHSAuth = IclOHSAuth(myAuthConfig, myDataSource)
```

Every call is synchronous/blocking (coroutines are hidden internally) and failures are
typed exceptions instead of HTTP status codes:

| Exception | Thrown by | Meaning |
|---|---|---|
| `InvalidCredentialsException` | `login` | Keycloak rejected the username/password |
| `AccountLockedException` | `login` | Too many recent failed attempts for this username |
| `TokenReuseDetectedException` | `refresh` | A replayed (already-rotated-away) refresh token was presented — the session is revoked |
| `SessionRevokedException` | `refresh` | The session was already revoked (e.g. prior logout) |
| `InvalidTokenException` | `profile`, `sessions`, `auditLog` | The token failed signature/issuer/audience verification |
| `InsufficientRoleException` | `auditLog` | Caller's token lacks `MOH_ADMIN`/`SUPER_ADMIN` |

`server/` is built the same way — `Main.kt` constructs the same `IclOHSAuth` and
`AuthModule.kt` just maps these calls/exceptions onto HTTP routes/status codes, so both
usage styles share one implementation.

## HTTP endpoints (`server/` only)

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
./gradlew :core:jvmTest        # commonTest (policy logic) + jvmTest (IclOHSAuth + services, Testcontainers Postgres)
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

Only `core` is published — it's the reusable library (`IclOHSAuth`, DTOs,
`KeycloakHttpClient`, policy logic); `server` is a runnable application, not something
other projects depend on.
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
  client-credentials grant) to actually update the password. This is stubbed identically
  whether called via `IclOHSAuth` or `/auth/password-reset/*`, since it isn't implemented
  in either yet.
- `androidTarget()` / `iosArm64()` are commented out in `core/build.gradle.kts` rather
  than removed — enabling them is the entire "make this a mobile client SDK" step once
  that's wanted.
- `IclOHSAuth`'s constructor takes an `AuthConfig` + a `DataSource` you build yourself —
  pointing it at a specific project's real Keycloak realm and Postgres instance (rather
  than the demo `.env`/`docker-compose.yml` setup here) is left to the host app.
