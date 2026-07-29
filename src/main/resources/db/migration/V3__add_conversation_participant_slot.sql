DO
$$
BEGIN
    IF EXISTS (
       SELECT conversation_id
       FROM conversation_participants
       GROUP BY conversation_id
       HAVING COUNT(*) > 2
    ) THEN
       RAISE EXCEPTION
            'participant_slot을 추가할 수 없습니다. 참여자가 2명을 초과한 대화가 존재합니다.';
    END IF;
END
$$;

ALTER TABLE conversation_participants
    ADD COLUMN participant_slot VARCHAR(10);

WITH ranked_participants AS (
    SELECT
        id,
        ROW_NUMBER() OVER (
            PARTITION BY conversation_id
            ORDER BY created_at, id
        ) AS participant_order
    FROM conversation_participants
)
UPDATE conversation_participants cp
SET participant_slot =
    CASE rp.participant_order
        WHEN 1 THEN 'FIRST'
        WHEN 2 THEN 'SECOND'
    END
FROM ranked_participants rp
WHERE cp.id = rp.id;

ALTER TABLE conversation_participants
    ALTER COLUMN participant_slot SET NOT NULL;

ALTER TABLE conversation_participants
    ADD CONSTRAINT ck_conversation_participants_slot
        CHECK (participant_slot IN ('FIRST', 'SECOND'));

ALTER TABLE conversation_participants
    ADD CONSTRAINT uk_conversation_participants_conversation_slot
        UNIQUE (conversation_id, participant_slot);
