CREATE TABLE ohs_auth.registration_otp (
    id                BIGSERIAL PRIMARY KEY,
    keycloak_user_id  UUID NOT NULL,
    username          TEXT NOT NULL,
    otp_hash          TEXT NOT NULL,
    expires_at        TIMESTAMPTZ NOT NULL,
    attempts          INT NOT NULL DEFAULT 0,
    consumed          BOOLEAN NOT NULL DEFAULT false,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_registration_otp_username ON ohs_auth.registration_otp (username);
