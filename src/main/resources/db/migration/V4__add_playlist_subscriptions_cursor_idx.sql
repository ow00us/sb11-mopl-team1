-- 플레이리스트 구독자 목록 조회 (커서 페이지네이션) 성능 최적화 인덱스
-- 관련 쿼리: PlaylistSubscriptionRepository.findByPlaylistIdDesc
--   WHERE playlist_id = :playlistId
--     AND (created_at, id) 커서 조건
--   ORDER BY created_at DESC, id ASC
-- 조합 인덱스로 Seq Scan → Index Scan 전환, 정렬·LIMIT 을 순차 읽기로 처리.
--
-- CREATE INDEX CONCURRENTLY: 인덱스 생성 중 대상 테이블의 INSERT/UPDATE/DELETE 가
-- 차단되지 않도록 한다. PostgreSQL 의 CONCURRENTLY 는 트랜잭션 안에서 실행할 수
-- 없으므로 동일 이름의 V4__...conf 에서 executeInTransaction=false 로 설정한다.
--
-- 재시도 절차: 이 마이그레이션이 실패하면 인덱스가 invalid 상태로 남을 수 있다.
-- 다음 순서로 정리 후 재적용한다.
--   1. SELECT indexrelid::regclass, indisvalid FROM pg_index
--        WHERE indexrelid = 'idx_playlist_subscriptions_playlist_id_created_at_id'::regclass;
--   2. indisvalid = false 이면
--        DROP INDEX CONCURRENTLY IF EXISTS idx_playlist_subscriptions_playlist_id_created_at_id;
--   3. Flyway repair 후 마이그레이션 재실행.
CREATE INDEX CONCURRENTLY idx_playlist_subscriptions_playlist_id_created_at_id
    ON playlist_subscriptions (playlist_id, created_at DESC, id ASC);