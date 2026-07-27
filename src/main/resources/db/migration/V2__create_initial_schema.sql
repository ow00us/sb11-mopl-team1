CREATE TABLE users (
    id                UUID PRIMARY KEY,
    created_at        TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    updated_at        TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    email             VARCHAR(255) NOT NULL,
    password_hash     VARCHAR(255) NOT NULL,
    name              VARCHAR(100) NOT NULL,
    profile_image_url VARCHAR(2048),
    role              VARCHAR(20) NOT NULL,
    locked            BOOLEAN NOT NULL DEFAULT FALSE,
    CONSTRAINT uk_users_email UNIQUE (email),
    CONSTRAINT ck_users_role CHECK (role IN ('USER', 'ADMIN'))
);

CREATE TABLE contents (
    id                UUID PRIMARY KEY,
    created_at        TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    updated_at        TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    type              VARCHAR(20) NOT NULL,
    source            VARCHAR(50),
    external_id       VARCHAR(255),
    title             VARCHAR(255) NOT NULL,
    description       TEXT NOT NULL,
    thumbnail_url     VARCHAR(2048),
    average_rating    NUMERIC(2, 1) NOT NULL DEFAULT 0.0,
    review_count      BIGINT NOT NULL DEFAULT 0,
    watcher_count     BIGINT NOT NULL DEFAULT 0,
    deleted_at        TIMESTAMP(6) WITH TIME ZONE,
    CONSTRAINT ck_contents_type CHECK (type IN ('MOVIE', 'TV_SERIES', 'SPORT')),
    CONSTRAINT ck_contents_external_source
        CHECK (external_id IS NULL OR source IS NOT NULL),
    CONSTRAINT ck_contents_average_rating
        CHECK (average_rating BETWEEN 0.0 AND 5.0),
    CONSTRAINT ck_contents_review_count CHECK (review_count >= 0),
    CONSTRAINT ck_contents_watcher_count CHECK (watcher_count >= 0)
);

CREATE UNIQUE INDEX uk_contents_source_external_id
    ON contents (source, external_id)
    WHERE external_id IS NOT NULL;

CREATE INDEX idx_contents_deleted_at ON contents (deleted_at);

CREATE TABLE content_tags (
    content_id UUID NOT NULL,
    tag        VARCHAR(100) NOT NULL,
    CONSTRAINT pk_content_tags PRIMARY KEY (content_id, tag),
    CONSTRAINT fk_content_tags_content
        FOREIGN KEY (content_id) REFERENCES contents (id) ON DELETE CASCADE,
    CONSTRAINT ck_content_tags_normalized
        CHECK (
            tag <> ''
            AND tag = LOWER(REGEXP_REPLACE(BTRIM(tag), '[[:space:]]+', ' ', 'g'))
        )
);

CREATE TABLE reviews (
    id         UUID PRIMARY KEY,
    created_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    author_id  UUID NOT NULL,
    content_id UUID NOT NULL,
    text       TEXT NOT NULL,
    rating     NUMERIC(2, 1) NOT NULL,
    CONSTRAINT fk_reviews_author
        FOREIGN KEY (author_id) REFERENCES users (id),
    CONSTRAINT fk_reviews_content
        FOREIGN KEY (content_id) REFERENCES contents (id),
    CONSTRAINT uk_reviews_author_content UNIQUE (author_id, content_id),
    CONSTRAINT ck_reviews_rating_range CHECK (rating BETWEEN 0.0 AND 5.0),
    CONSTRAINT ck_reviews_rating_step CHECK (MOD(rating * 10, 5) = 0)
);

CREATE INDEX idx_reviews_content_id ON reviews (content_id);

CREATE TABLE playlists (
    id               UUID PRIMARY KEY,
    created_at       TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    updated_at       TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    owner_id         UUID NOT NULL,
    title            VARCHAR(255) NOT NULL,
    description      TEXT NOT NULL,
    subscriber_count BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT fk_playlists_owner
        FOREIGN KEY (owner_id) REFERENCES users (id),
    CONSTRAINT ck_playlists_subscriber_count CHECK (subscriber_count >= 0)
);

CREATE INDEX idx_playlists_owner_id ON playlists (owner_id);

CREATE TABLE playlist_contents (
    id          UUID PRIMARY KEY,
    created_at  TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    updated_at  TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    playlist_id UUID NOT NULL,
    content_id  UUID NOT NULL,
    CONSTRAINT fk_playlist_contents_playlist
        FOREIGN KEY (playlist_id) REFERENCES playlists (id) ON DELETE CASCADE,
    CONSTRAINT fk_playlist_contents_content
        FOREIGN KEY (content_id) REFERENCES contents (id),
    CONSTRAINT uk_playlist_contents_playlist_content
        UNIQUE (playlist_id, content_id)
);

CREATE INDEX idx_playlist_contents_content_id
    ON playlist_contents (content_id);

CREATE TABLE playlist_subscriptions (
    id            UUID PRIMARY KEY,
    created_at    TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    updated_at    TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    playlist_id   UUID NOT NULL,
    subscriber_id UUID NOT NULL,
    CONSTRAINT fk_playlist_subscriptions_playlist
        FOREIGN KEY (playlist_id) REFERENCES playlists (id) ON DELETE CASCADE,
    CONSTRAINT fk_playlist_subscriptions_subscriber
        FOREIGN KEY (subscriber_id) REFERENCES users (id),
    CONSTRAINT uk_playlist_subscriptions_playlist_subscriber
        UNIQUE (playlist_id, subscriber_id)
);

CREATE INDEX idx_playlist_subscriptions_subscriber_id
    ON playlist_subscriptions (subscriber_id);

CREATE TABLE follows (
    id          UUID PRIMARY KEY,
    created_at  TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    updated_at  TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    follower_id UUID NOT NULL,
    followee_id UUID NOT NULL,
    CONSTRAINT fk_follows_follower
        FOREIGN KEY (follower_id) REFERENCES users (id),
    CONSTRAINT fk_follows_followee
        FOREIGN KEY (followee_id) REFERENCES users (id),
    CONSTRAINT uk_follows_follower_followee UNIQUE (follower_id, followee_id),
    CONSTRAINT ck_follows_not_self CHECK (follower_id <> followee_id)
);

CREATE INDEX idx_follows_followee_id ON follows (followee_id);

CREATE TABLE conversations (
    id         UUID PRIMARY KEY,
    created_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP(6) WITH TIME ZONE NOT NULL
);

CREATE TABLE conversation_participants (
    id              UUID PRIMARY KEY,
    created_at      TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    updated_at      TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    conversation_id UUID NOT NULL,
    user_id         UUID NOT NULL,
    CONSTRAINT fk_conversation_participants_conversation
        FOREIGN KEY (conversation_id) REFERENCES conversations (id) ON DELETE CASCADE,
    CONSTRAINT fk_conversation_participants_user
        FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT uk_conversation_participants_conversation_user
        UNIQUE (conversation_id, user_id)
);

CREATE INDEX idx_conversation_participants_user_id
    ON conversation_participants (user_id);

CREATE TABLE direct_messages (
    id              UUID PRIMARY KEY,
    created_at      TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    updated_at      TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    conversation_id UUID NOT NULL,
    sender_id       UUID NOT NULL,
    content         TEXT NOT NULL,
    read_at         TIMESTAMP(6) WITH TIME ZONE,
    CONSTRAINT fk_direct_messages_conversation
        FOREIGN KEY (conversation_id) REFERENCES conversations (id) ON DELETE CASCADE,
    CONSTRAINT fk_direct_messages_sender
        FOREIGN KEY (sender_id) REFERENCES users (id)
);

CREATE INDEX idx_direct_messages_conversation_created_at
    ON direct_messages (conversation_id, created_at DESC);

CREATE INDEX idx_direct_messages_sender_id
    ON direct_messages (sender_id);

CREATE TABLE notifications (
    id              UUID PRIMARY KEY,
    created_at      TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    updated_at      TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    receiver_id     UUID NOT NULL,
    source_event_id UUID,
    title           VARCHAR(255) NOT NULL,
    content         TEXT NOT NULL,
    level           VARCHAR(20) NOT NULL,
    read_at         TIMESTAMP(6) WITH TIME ZONE,
    CONSTRAINT fk_notifications_receiver
        FOREIGN KEY (receiver_id) REFERENCES users (id),
    CONSTRAINT ck_notifications_level CHECK (level IN ('INFO', 'WARNING', 'ERROR'))
);

CREATE UNIQUE INDEX uk_notifications_source_event_id
    ON notifications (source_event_id)
    WHERE source_event_id IS NOT NULL;

CREATE INDEX idx_notifications_receiver_read_at
    ON notifications (receiver_id, read_at);

CREATE TABLE watching_session_snapshots (
    id         UUID PRIMARY KEY,
    created_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    watcher_id UUID NOT NULL,
    content_id UUID NOT NULL,
    expires_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    CONSTRAINT fk_watching_session_snapshots_watcher
        FOREIGN KEY (watcher_id) REFERENCES users (id),
    CONSTRAINT fk_watching_session_snapshots_content
        FOREIGN KEY (content_id) REFERENCES contents (id),
    CONSTRAINT uk_watching_session_snapshots_watcher UNIQUE (watcher_id)
);

CREATE INDEX idx_watching_session_snapshots_content_id
    ON watching_session_snapshots (content_id);

CREATE INDEX idx_watching_session_snapshots_expires_at
    ON watching_session_snapshots (expires_at);
