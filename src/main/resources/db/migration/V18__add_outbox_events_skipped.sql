-- Outbox SKIPPED 전환과 처리 감사 정보
--
-- 공통 계약에는 SKIPPED 가 정의되어 있지만 체크 제약에 빠져 있어 계약과 런타임이 어긋나
-- 있었다. 상태를 넣고, 그 상태가 무엇을 뜻하는지 증명하는 정보를 함께 요구한다.
--
-- SKIPPED 는 발행에 성공했다는 뜻이 아니다. 이벤트를 보내지 않아도 된다는 업무 판단과 그
-- 책임을 남기는 종결 상태다. 그래서 누가, 언제, 왜 라는 세 가지가 없으면 단순히 지운 것과
-- 구분되지 않는다.
ALTER TABLE outbox_events
    ADD COLUMN skipped_by  UUID,
    ADD COLUMN skipped_at  TIMESTAMP(6) WITH TIME ZONE,
    ADD COLUMN skip_reason TEXT;

ALTER TABLE outbox_events
    DROP CONSTRAINT ck_outbox_events_status;

ALTER TABLE outbox_events
    ADD CONSTRAINT ck_outbox_events_status
        CHECK (status IN ('PENDING', 'PUBLISHED', 'FAILED', 'EXPIRED', 'SKIPPED'));

-- 감사 정보와 상태를 한쪽 방향이 아니라 양방향으로 묶는다.
--
-- SKIPPED 인데 감사 정보가 비어 있으면 나중에 그 행을 보고 무슨 일이 있었는지 알 수 없다.
-- 반대로 다른 상태에 감사 정보가 남아 있으면 건너뛴 적이 있는 이벤트로 잘못 읽힌다.
-- SKIPPED 는 종결 상태이므로 여기서 빠져나가는 전이는 없다.
ALTER TABLE outbox_events
    ADD CONSTRAINT ck_outbox_events_skip_audit
        CHECK (
            (status <> 'SKIPPED'
                AND skipped_by IS NULL
                AND skipped_at IS NULL
                AND skip_reason IS NULL)
            OR (status = 'SKIPPED'
                AND skipped_by IS NOT NULL
                AND skipped_at IS NOT NULL
                AND skip_reason IS NOT NULL
                AND length(btrim(skip_reason)) > 0)
        );
