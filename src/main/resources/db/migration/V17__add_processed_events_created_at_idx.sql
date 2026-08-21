-- 멱등 처리 기록 정리용 인덱스
--
-- 정리 작업이 보관 기간을 지난 행을 기록 시각이 이른 순으로 지운다. 인덱스가 없으면 매
-- 실행이 전체 테이블을 훑는데, 정리가 필요한 상황이란 곧 그 테이블이 크다는 뜻이라
-- 정리를 시작하는 시점이 가장 비싼 시점이 된다.
--
-- 부분 인덱스로 두지 않는다. outbox_events 와 달리 상태 컬럼이 없어 모든 행이 정리 대상이다.
CREATE INDEX idx_processed_events_created_at
    ON processed_events (created_at, id);
