ALTER TABLE users
    ADD COLUMN deleted_at TIMESTAMP(6) WITH TIME ZONE;

CREATE INDEX idx_users_deleted_at
    ON users (deleted_at);
