CREATE TABLE refresh_token_sessions
(
    id         UUID PRIMARY KEY,
    created_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    user_id    UUID                        NOT NULL,
    token_hash VARCHAR(64)                 NOT NULL,
    expires_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    revoked_at TIMESTAMP(6) WITH TIME ZONE,

    CONSTRAINT fk_refresh_token_sessions_user
        FOREIGN KEY (user_id)
            REFERENCES users (id)
            ON DELETE CASCADE,

    CONSTRAINT uk_refresh_token_sessions_token_hash
        UNIQUE (token_hash),

    CONSTRAINT ck_refresh_token_sessions_expiration
        CHECK (expires_at > created_at),

    CONSTRAINT ck_refresh_token_sessions_revocation
        CHECK (
            revoked_at IS NULL
                OR revoked_at >= created_at
            )
);

CREATE INDEX idx_refresh_token_sessions_user_id
    ON refresh_token_sessions (user_id);
