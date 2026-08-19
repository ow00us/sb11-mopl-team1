-- 사건 단위 중복 Outbox 기록 방지
--
-- event_id 유니크가 막는 것은 같은 envelope 를 두 번 넘기는 경우다. 도메인 연산이 두 번
-- 실행되어 envelope 를 각각 새로 만들면 event_id 가 서로 달라 두 행이 모두 저장되고
-- 이벤트가 두 번 발행된다. 계약 §9 는 이 지점을 사건별 deduplication key 로 정해 두었다.
--
-- 지금은 호출 계약("실제 상태 변화가 있을 때만 기록한다")만으로 막는다. 도메인이 그 판단을
-- 한 곳에서라도 놓치면 결과가 사용자에게 중복 알림으로 나타나므로, 스키마가 잡아주게 한다.
ALTER TABLE outbox_events
    ADD COLUMN deduplication_key VARCHAR(200);

-- 이 컬럼이 생기기 전에 기록된 행을 채운다. 개발 환경처럼 이미 행이 있는 데이터베이스에서
-- NOT NULL 컬럼을 바로 추가하면 마이그레이션이 실패한다.
--
-- 채우는 값은 사건이 아니라 레코드를 가리킨다. 규칙이 생기기 전의 행이라 사건 식별자를
-- 되살릴 방법이 없다. event_id 가 유니크하므로 아래 유니크 제약과 충돌하지 않는다.
UPDATE outbox_events
SET deduplication_key = type || ':' || event_id
WHERE deduplication_key IS NULL;

ALTER TABLE outbox_events
    ALTER COLUMN deduplication_key SET NOT NULL;

-- 같은 사건의 두 번째 기록을 여기서 거부한다. 기록은 도메인 트랜잭션 안에서 일어나므로
-- 거부되면 도메인 변경도 함께 롤백된다.
ALTER TABLE outbox_events
    ADD CONSTRAINT uk_outbox_events_deduplication_key UNIQUE (deduplication_key);
