\set ON_ERROR_STOP on

BEGIN;

DO $$
DECLARE
    target_tables TEXT;
BEGIN
    SELECT string_agg(format('%I', table_name), ', ' ORDER BY table_name)
    INTO target_tables
    FROM information_schema.tables
    WHERE table_schema = 'public'
      AND table_type = 'BASE TABLE'
      AND table_name <> 'flyway_schema_history';

    IF target_tables IS NOT NULL THEN
        EXECUTE 'TRUNCATE TABLE ' || target_tables || ' RESTART IDENTITY CASCADE';
    END IF;
END
$$;

\copy users (id, created_at, updated_at, email, password_hash, name, profile_image_url, role, locked) FROM '/tmp/mopl-staging-dataset/users.csv' WITH (FORMAT csv, HEADER true, NULL '');
\copy contents (id, created_at, updated_at, type, source, external_id, title, description, thumbnail_url, average_rating, review_count, watcher_count, deleted_at) FROM '/tmp/mopl-staging-dataset/contents.csv' WITH (FORMAT csv, HEADER true, NULL '');
\copy content_tags (content_id, tag) FROM '/tmp/mopl-staging-dataset/content_tags.csv' WITH (FORMAT csv, HEADER true, NULL '');
\copy reviews (id, created_at, updated_at, author_id, content_id, text, rating) FROM '/tmp/mopl-staging-dataset/reviews.csv' WITH (FORMAT csv, HEADER true, NULL '');
\copy playlists (id, created_at, updated_at, owner_id, title, description, subscriber_count) FROM '/tmp/mopl-staging-dataset/playlists.csv' WITH (FORMAT csv, HEADER true, NULL '');
\copy playlist_contents (id, created_at, updated_at, playlist_id, content_id) FROM '/tmp/mopl-staging-dataset/playlist_contents.csv' WITH (FORMAT csv, HEADER true, NULL '');
\copy playlist_subscriptions (id, created_at, updated_at, playlist_id, subscriber_id) FROM '/tmp/mopl-staging-dataset/playlist_subscriptions.csv' WITH (FORMAT csv, HEADER true, NULL '');
\copy follows (id, created_at, updated_at, follower_id, followee_id) FROM '/tmp/mopl-staging-dataset/follows.csv' WITH (FORMAT csv, HEADER true, NULL '');
\copy conversations (id, created_at, updated_at, participant_pair_key, next_message_sequence) FROM '/tmp/mopl-staging-dataset/conversations.csv' WITH (FORMAT csv, HEADER true, NULL '');
\copy conversation_participants (id, created_at, updated_at, conversation_id, user_id, participant_slot) FROM '/tmp/mopl-staging-dataset/conversation_participants.csv' WITH (FORMAT csv, HEADER true, NULL '');
\copy direct_messages (id, created_at, updated_at, conversation_id, sender_id, content, read_at, message_sequence) FROM '/tmp/mopl-staging-dataset/direct_messages.csv' WITH (FORMAT csv, HEADER true, NULL '');
\copy notifications (id, created_at, updated_at, receiver_id, source_event_id, title, content, level, read_at, type, resource_id, source_entity_id) FROM '/tmp/mopl-staging-dataset/notifications.csv' WITH (FORMAT csv, HEADER true, NULL '');

COMMIT;
