-- 순서 게이트용 부분 인덱스로 교체
--
-- 계약 §9 순서 게이트는 같은 partition key 안에서 앞선 이벤트가 끝나기 전에 뒤 이벤트를
-- 발행하지 않는다. 선점 조회가 후보마다 "같은 키의 앞선 미완료 건이 있는가"를 확인한다.
--
-- 기존 idx_outbox_events_partition_order 는 상태를 가리지 않아 발행을 마친 행까지 포함한다.
-- 발행 완료 행은 계속 쌓이므로, 한 키에 이벤트가 많아질수록 게이트 확인이 그 키의 과거
-- 전체를 훑는다. 확인해야 하는 것은 아직 끝나지 않은 행뿐이다.
--
-- 부분 인덱스는 미완료 행만 담는다. 정상 운영에서 그 수는 적게 유지되므로 게이트 확인 비용이
-- 키의 누적 이벤트 수와 무관해진다.
DROP INDEX idx_outbox_events_partition_order;

CREATE INDEX idx_outbox_events_partition_gate
    ON outbox_events (partition_key, occurred_at, id)
    WHERE status IN ('PENDING', 'FAILED');
