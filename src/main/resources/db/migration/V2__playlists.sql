CREATE TABLE playlists (
    id               UUID PRIMARY KEY,
    created_at       TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    updated_at       TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    owner_id         UUID         NOT NULL,
    title            VARCHAR(200) NOT NULL,
    description      TEXT         NOT NULL,
    subscriber_count BIGINT       NOT NULL DEFAULT 0
);