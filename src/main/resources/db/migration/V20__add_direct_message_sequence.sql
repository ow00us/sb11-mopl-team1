ALTER TABLE conversations
    ADD COLUMN next_message_sequence BIGINT NOT NULL DEFAULT 0;

ALTER TABLE direct_messages
    ADD COLUMN message_sequence BIGINT;

WITH ordered_messages AS (
    SELECT
        id,
        ROW_NUMBER() OVER (
            PARTITION BY conversation_id
            ORDER BY created_at, id
        ) AS message_sequence
    FROM direct_messages
)
UPDATE direct_messages message
SET message_sequence = ordered.message_sequence
FROM ordered_messages ordered
WHERE message.id = ordered.id;

UPDATE conversations conversation
SET next_message_sequence = COALESCE(
    (
        SELECT MAX(message.message_sequence)
        FROM direct_messages message
        WHERE message.conversation_id = conversation.id
    ),
    0
);

ALTER TABLE direct_messages
    ALTER COLUMN message_sequence SET NOT NULL;

ALTER TABLE direct_messages
    ADD CONSTRAINT ck_direct_messages_message_sequence_positive
        CHECK (message_sequence > 0);

ALTER TABLE direct_messages
    ADD CONSTRAINT uk_direct_messages_conversation_sequence
        UNIQUE (conversation_id, message_sequence);
