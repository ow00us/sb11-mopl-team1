\set ON_ERROR_STOP on

SELECT 'users=' || COUNT(*) FROM users;
SELECT 'contents=' || COUNT(*) FROM contents;
SELECT 'content_tags=' || COUNT(*) FROM content_tags;
SELECT 'reviews=' || COUNT(*) FROM reviews;
SELECT 'playlists=' || COUNT(*) FROM playlists;
SELECT 'playlist_contents=' || COUNT(*) FROM playlist_contents;
SELECT 'playlist_subscriptions=' || COUNT(*) FROM playlist_subscriptions;
SELECT 'follows=' || COUNT(*) FROM follows;
SELECT 'conversations=' || COUNT(*) FROM conversations;
SELECT 'conversation_participants=' || COUNT(*) FROM conversation_participants;
SELECT 'direct_messages=' || COUNT(*) FROM direct_messages;
SELECT 'notifications=' || COUNT(*) FROM notifications;

WITH review_aggregates AS (
    SELECT content_id, COUNT(*) AS review_count, ROUND(AVG(rating), 1) AS average_rating
    FROM reviews
    GROUP BY content_id
)
SELECT 'content_aggregate_mismatches=' || COUNT(*)
FROM contents content
LEFT JOIN review_aggregates aggregate ON aggregate.content_id = content.id
WHERE content.review_count <> COALESCE(aggregate.review_count, 0)
   OR content.average_rating <> COALESCE(aggregate.average_rating, 0.0);

WITH subscription_aggregates AS (
    SELECT playlist_id, COUNT(*) AS subscriber_count
    FROM playlist_subscriptions
    GROUP BY playlist_id
)
SELECT 'playlist_aggregate_mismatches=' || COUNT(*)
FROM playlists playlist
LEFT JOIN subscription_aggregates aggregate ON aggregate.playlist_id = playlist.id
WHERE playlist.subscriber_count <> COALESCE(aggregate.subscriber_count, 0);

SELECT 'conversation_participant_mismatches=' || COUNT(*)
FROM (
    SELECT conversation.id
    FROM conversations conversation
    LEFT JOIN conversation_participants participant
        ON participant.conversation_id = conversation.id
    GROUP BY conversation.id
    HAVING COUNT(participant.id) <> 2
       OR COUNT(DISTINCT participant.participant_slot) <> 2
) mismatch;

SELECT 'conversation_pair_key_mismatches=' || COUNT(*)
FROM conversations conversation
WHERE conversation.participant_pair_key <> (
    SELECT MIN(participant.user_id::text) || ':' || MAX(participant.user_id::text)
    FROM conversation_participants participant
    WHERE participant.conversation_id = conversation.id
);

SELECT 'dm_sender_mismatches=' || COUNT(*)
FROM direct_messages message
WHERE NOT EXISTS (
    SELECT 1
    FROM conversation_participants participant
    WHERE participant.conversation_id = message.conversation_id
      AND participant.user_id = message.sender_id
);

WITH message_sequences AS (
    SELECT
        conversation_id,
        COUNT(*) AS message_count,
        MIN(message_sequence) AS minimum_sequence,
        MAX(message_sequence) AS maximum_sequence,
        COUNT(DISTINCT message_sequence) AS distinct_sequence_count
    FROM direct_messages
    GROUP BY conversation_id
)
SELECT 'dm_sequence_mismatches=' || COUNT(*)
FROM conversations conversation
LEFT JOIN message_sequences sequence ON sequence.conversation_id = conversation.id
WHERE COALESCE(sequence.minimum_sequence, 0) <> CASE WHEN COALESCE(sequence.message_count, 0) = 0 THEN 0 ELSE 1 END
   OR COALESCE(sequence.maximum_sequence, 0) <> COALESCE(sequence.message_count, 0)
   OR COALESCE(sequence.distinct_sequence_count, 0) <> COALESCE(sequence.message_count, 0)
   OR conversation.next_message_sequence <> COALESCE(sequence.message_count, 0);

SELECT 'notification_mapping_mismatches=' || COUNT(*)
FROM notifications notification
WHERE (notification.type = 'FOLLOW' AND NOT EXISTS (
        SELECT 1
        FROM follows follow
        WHERE follow.id = notification.source_entity_id
          AND follow.followee_id = notification.receiver_id
          AND follow.follower_id = notification.resource_id
    ))
   OR (notification.type = 'PLAYLIST_SUBSCRIPTION' AND NOT EXISTS (
        SELECT 1
        FROM playlist_subscriptions subscription
        JOIN playlists playlist ON playlist.id = subscription.playlist_id
        WHERE subscription.id = notification.source_entity_id
          AND playlist.owner_id = notification.receiver_id
          AND playlist.id = notification.resource_id
    ))
   OR (notification.type = 'DIRECT_MESSAGE' AND NOT EXISTS (
        SELECT 1
        FROM direct_messages message
        JOIN conversation_participants participant
          ON participant.conversation_id = message.conversation_id
         AND participant.user_id <> message.sender_id
        WHERE message.id = notification.source_entity_id
          AND message.conversation_id = notification.resource_id
          AND participant.user_id = notification.receiver_id
    ))
   OR notification.type IS NULL
   OR notification.type NOT IN ('FOLLOW', 'PLAYLIST_SUBSCRIPTION', 'DIRECT_MESSAGE');
