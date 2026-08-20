-- 콘텐츠 검색 동기화 재시도 대기열
--
-- ContentSearchKeyedExecutor 레인 큐가 가득 차 sync/delete 이벤트가 거부되면 여기에 남겨
-- ContentSearchRetryScheduler가 주기적으로 재적용한다. outbox_events의 claim·lease 구조를
-- 그대로 따른다 — 여러 인스턴스가 동시에 재시도를 돌려도 같은 행을 두 번 처리하지 않아야
-- 하기 때문이다(ECS 다중 태스크 배포 대상).
CREATE TABLE content_search_retries (
    id                UUID PRIMARY KEY,
    created_at        TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    updated_at        TIMESTAMP(6) WITH TIME ZONE NOT NULL,

    content_id        UUID NOT NULL,
    event_type        VARCHAR(20) NOT NULL,

    status            VARCHAR(20) NOT NULL,
    attempts          INTEGER NOT NULL DEFAULT 0,
    next_attempt_at   TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    claim_owner       VARCHAR(100),
    claim_expires_at  TIMESTAMP(6) WITH TIME ZONE,
    last_error        TEXT,

    CONSTRAINT ck_content_search_retries_event_type CHECK (event_type IN ('SYNC', 'DELETE')),
    CONSTRAINT ck_content_search_retries_status CHECK (status IN ('PENDING', 'COMPLETED', 'FAILED'))
);

-- 재시도 대상 조회와 claim 대상 선정에 쓴다. 부분 인덱스인 이유는 COMPLETED/FAILED 행이
-- 계속 쌓여도 인덱스 크기가 커지지 않게 하기 위해서다.
CREATE INDEX idx_content_search_retries_pending
    ON content_search_retries (next_attempt_at, id)
    WHERE status = 'PENDING';

-- 만료된 claim 회수에 쓴다.
CREATE INDEX idx_content_search_retries_claim_expires_at
    ON content_search_retries (claim_expires_at)
    WHERE claim_owner IS NOT NULL;
