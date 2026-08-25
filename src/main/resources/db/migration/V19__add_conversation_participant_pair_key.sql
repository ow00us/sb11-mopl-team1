ALTER TABLE conversations
    ADD COLUMN participant_pair_key VARCHAR(73);

UPDATE conversations conversation
SET participant_pair_key =
    participant_pair.first_user_id
        || ':'
        || participant_pair.second_user_id
FROM (
    SELECT
        participant.conversation_id,
        MIN(participant.user_id::text)
            AS first_user_id,
        MAX(participant.user_id::text)
            AS second_user_id,
        COUNT(*) AS participant_count
    FROM conversation_participants participant
    GROUP BY participant.conversation_id
) participant_pair
WHERE conversation.id =
        participant_pair.conversation_id
    AND participant_pair.participant_count = 2;

DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM conversations
        WHERE participant_pair_key IS NULL
    ) THEN
        RAISE EXCEPTION
            'Conversation 참여자는 정확히 2명이어야 합니다.';
    END IF;

    IF EXISTS (
        SELECT participant_pair_key
        FROM conversations
        GROUP BY participant_pair_key
        HAVING COUNT(*) > 1
    ) THEN
        RAISE EXCEPTION
            '동일 사용자 쌍의 Conversation이 중복되어 있습니다.';
    END IF;
END
$$;

ALTER TABLE conversations
    ALTER COLUMN participant_pair_key
        SET NOT NULL;

ALTER TABLE conversations
    ADD CONSTRAINT
        uk_conversations_participant_pair_key
    UNIQUE (participant_pair_key);
