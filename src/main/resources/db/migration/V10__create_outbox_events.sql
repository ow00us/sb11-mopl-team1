-- Outbox 이벤트 저장
--
-- 도메인 상태 변경과 이벤트 기록을 같은 트랜잭션에 묶어, 상태는 바뀌었는데 이벤트만
-- 유실되는 경우를 없앤다. 커밋된 행을 relay 가 읽어 Kafka 에 발행한다.
--
-- 이 마이그레이션은 저장 기반만 만든다. 기록 포트(#229), claim·lease(#230),
-- relay 발행(#231)은 후속 이슈다.
CREATE TABLE outbox_events (
    id                UUID PRIMARY KEY,
    created_at        TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    updated_at        TIMESTAMP(6) WITH TIME ZONE NOT NULL,

    -- envelope 원본. relay 가 이 값들로 발행 메시지를 다시 만든다.
    event_id          UUID NOT NULL,
    type              VARCHAR(100) NOT NULL,
    version           INTEGER NOT NULL,
    aggregate_id      UUID NOT NULL,
    occurred_at       TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    payload           JSONB NOT NULL,

    -- 발행 대상 결정. partition_key 는 카탈로그가 이벤트별로 정한 값이고,
    -- ordering_scope 는 그 키로 무엇을 보장하는지를 남긴다(NONE, AGGREGATE, 업무 키).
    partition_key     VARCHAR(200) NOT NULL,
    ordering_scope    VARCHAR(50) NOT NULL,

    -- relay 진행 상태
    status            VARCHAR(20) NOT NULL,
    attempts          INTEGER NOT NULL DEFAULT 0,
    next_attempt_at   TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    claim_owner       VARCHAR(100),
    claim_expires_at  TIMESTAMP(6) WITH TIME ZONE,
    published_at      TIMESTAMP(6) WITH TIME ZONE,
    last_error        TEXT,

    CONSTRAINT uk_outbox_events_event_id UNIQUE (event_id),
    CONSTRAINT ck_outbox_events_status
        CHECK (status IN ('PENDING', 'PUBLISHED', 'FAILED', 'EXPIRED'))
);

-- 발행 대기 이벤트 조회와 claim 대상 선정에 쓴다.
-- 부분 인덱스로 둔 이유는 PUBLISHED 행이 계속 쌓이기 때문이다. 전체 인덱스면
-- 대기 건이 없어도 인덱스가 커진다.
CREATE INDEX idx_outbox_events_pending
    ON outbox_events (next_attempt_at, id)
    WHERE status = 'PENDING';

-- 만료된 claim 회수에 쓴다. 소유자가 있는 행만 대상이다.
CREATE INDEX idx_outbox_events_claim_expires_at
    ON outbox_events (claim_expires_at)
    WHERE claim_owner IS NOT NULL;

-- 같은 partition_key 의 발행 순서를 확인할 때 쓴다. 계약상 앞선 이벤트가 끝나기
-- 전에 뒤 이벤트를 발행하지 않아야 하므로, 키 안에서 occurred_at·id 순으로 읽는다.
CREATE INDEX idx_outbox_events_partition_order
    ON outbox_events (partition_key, occurred_at, id);
