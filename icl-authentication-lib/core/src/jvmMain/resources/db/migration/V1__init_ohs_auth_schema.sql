-- Bundled inside this service's fat jar under db/migration and run automatically by
-- Flyway the first time it boots against a fresh database. The backend team's Postgres
-- instance is never created or managed by this service - only this schema and the
-- tables inside it.

CREATE SCHEMA IF NOT EXISTS ohs_auth;

CREATE TABLE ohs_auth.refresh_token_family (
    family_id       UUID PRIMARY KEY,
    user_id         UUID NOT NULL,
    current_token   UUID NOT NULL,
    issued_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at      TIMESTAMPTZ NOT NULL,
    revoked         BOOLEAN NOT NULL DEFAULT false,
    revoked_reason  TEXT
);

CREATE TABLE ohs_auth.sessions (
    session_id   UUID PRIMARY KEY,
    user_id      UUID NOT NULL,
    family_id    UUID NOT NULL REFERENCES ohs_auth.refresh_token_family,
    device       TEXT,
    ip_address   TEXT,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_seen_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    revoked      BOOLEAN NOT NULL DEFAULT false
);

CREATE TABLE ohs_auth.login_attempts (
    id           BIGSERIAL PRIMARY KEY,
    username     TEXT NOT NULL,
    realm        TEXT NOT NULL,
    ip_address   TEXT,
    succeeded    BOOLEAN NOT NULL,
    attempted_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_login_attempts_lockout
    ON ohs_auth.login_attempts (username, realm, attempted_at DESC);

CREATE TABLE ohs_auth.audit_log (
    id          BIGSERIAL PRIMARY KEY,
    event       TEXT NOT NULL,
                -- LOGIN_SUCCESS, LOGIN_FAILED, TOKEN_REFRESH,
                -- TOKEN_REUSE_DETECTED, LOGOUT, PERMISSION_DENIED
    user_id     UUID,
    realm       TEXT NOT NULL,
    ip_address  TEXT,
    user_agent  TEXT,
    metadata    TEXT,
    prev_hash   TEXT,
    hash        TEXT NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_audit_log_user_time ON ohs_auth.audit_log (user_id, occurred_at DESC);
CREATE INDEX idx_audit_log_event ON ohs_auth.audit_log (event);
