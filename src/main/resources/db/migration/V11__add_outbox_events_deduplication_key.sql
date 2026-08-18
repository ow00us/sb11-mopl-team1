-- Outbox 사건 단위 중복 기록 방지
--
-- 같은 도메인 사건으로 Outbox 가 두 번 생성되지 않도록 생산자가 사건별
-- deduplicationKey 를 만든다. 예를 들면 follow.created:<followId> 형식이다.
-- UNIQUE 인덱스로 두 번째 INSERT 를 데이터베이스가 거부한다.
--
-- 컬럼 크기는 V10 의 partition_key 와 같은 VARCHAR(200) 이다. 계약의 예시 값이
-- 이벤트 타입 접두어(최대 30자) 와 UUID(36자) 조합으로 100자 미만이지만, 후속
-- 이벤트 타입이 늘어날 여유를 함께 둔다.
--
-- 참조: docs/07-kafka-outbox-contract.md §9
ALTER TABLE outbox_events
    ADD COLUMN deduplication_key VARCHAR(200) NOT NULL;

CREATE UNIQUE INDEX uk_outbox_events_deduplication_key
    ON outbox_events (deduplication_key);