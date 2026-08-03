-- 플레이리스트 구독자 목록 조회 (커서 페이지네이션) 성능 최적화 인덱스
-- 관련 쿼리: PlaylistSubscriptionRepository.findByPlaylistIdDesc
--   WHERE playlist_id = :playlistId
--     AND (created_at, id) 커서 조건
--   ORDER BY created_at DESC, id ASC
-- 조합 인덱스로 Seq Scan → Index Scan 전환, 정렬·LIMIT 을 순차 읽기로 처리.
CREATE INDEX idx_playlist_subscriptions_playlist_id_created_at_id
    ON playlist_subscriptions (playlist_id, created_at DESC, id ASC);