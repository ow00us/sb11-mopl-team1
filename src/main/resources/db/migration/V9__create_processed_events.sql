-- Kafka Consumer 멱등 처리 기록
--
-- at-least-once 전달에서 같은 이벤트가 다시 와도 도메인 부수 효과를 한 번만 수행하기
-- 위한 공통 경계다. (consumer_name, event_id) 유니크 제약이 최종 심판이며,
-- 애플리케이션의 사전 조회는 최적화일 뿐 동시 처리를 막지 못한다.
--
-- 알림 도메인은 이 테이블을 쓰지 않는다. 알림은 (source_event_id, receiver_id) 로
-- 수신자 단위 중복을 막으며, 이벤트 단위 기록을 함께 쓰면 일부 수신자만 저장된 상태에서
-- 재시도할 때 남은 수신자의 알림이 영구히 누락된다.
--
-- updated_at 을 두지 않는다. 처리 기록은 한 번 쓰고 갱신하지 않는다.
CREATE TABLE processed_events (
    id            UUID PRIMARY KEY,
    created_at    TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    consumer_name VARCHAR(100) NOT NULL,
    event_id      UUID NOT NULL,
    event_type    VARCHAR(100) NOT NULL,
    CONSTRAINT uk_processed_events_consumer_event UNIQUE (consumer_name, event_id)
);

-- 같은 이벤트를 어느 Consumer 들이 처리했는지 조회한다. #235 의 수동 replay 도구가
-- eventId 로 처리 상태를 확인할 때 쓴다.
CREATE INDEX idx_processed_events_event_id
    ON processed_events (event_id);
