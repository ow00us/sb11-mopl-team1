-- 최종 실패한 Outbox 이벤트 조회용 인덱스
--
-- 지표 수집이 최종 실패 건수를 주기적으로 세고, 운영자 재처리 경계가 같은 조건을
-- 발생 순으로 조회한다. 인덱스가 없으면 두 경로 모두 전체 테이블을 훑는데,
-- outbox_events 에는 발행을 마친 행이 계속 쌓이므로 시간이 갈수록 비용이 커진다.
--
-- 부분 인덱스로 둔 이유는 최종 실패가 드물기 때문이다. 전체 인덱스면 실패 건이 없어도
-- 발행 완료 행 전부가 인덱스에 들어간다.
CREATE INDEX idx_outbox_events_failed
    ON outbox_events (occurred_at, id)
    WHERE status = 'FAILED';
