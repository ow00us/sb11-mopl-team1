package com.mopl.watchingsession.repository;

import com.mopl.watchingsession.entity.WatchingSessionSnapshot;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface WatchingSessionSnapshotRepository extends JpaRepository<WatchingSessionSnapshot, UUID> {

    // 사용자당 활성 세션 1개 -> 단건 조회, 삭제만 필요
    Optional<WatchingSessionSnapshot> findByWatcherId(UUID watcherId);

    @Transactional
    void deleteByWatcherId(UUID watcherId);

    // 콘텐츠 기준 시청 세션 목록 - 첫 페이지, 최신순(내림차순)
    @Query("""
      SELECT s FROM WatchingSessionSnapshot s
      JOIN User u ON u.id = s.watcherId
      WHERE s.contentId = :contentId
        AND s.expiresAt > :now
        AND (cast(:watcherNameLike as string) IS NULL OR LOWER(u.name) LIKE LOWER(CONCAT('%', cast(:watcherNameLike as string), '%')) ESCAPE '\\')
      ORDER BY s.createdAt DESC, s.id DESC
      """)
    List<WatchingSessionSnapshot> findByContentIdFirstPageDesc(
        @Param("contentId") UUID contentId,
        @Param("watcherNameLike") String watcherNameLike,
        @Param("now") Instant now,
        Pageable pageable
    );

    // 콘텐츠 기준 시청 세션 목록 - 커서 이후, 최신순(내림차순)
    @Query("""
      SELECT s FROM WatchingSessionSnapshot s
      JOIN User u ON u.id = s.watcherId
      WHERE s.contentId = :contentId
        AND s.expiresAt > :now
        AND (cast(:watcherNameLike as string) IS NULL OR LOWER(u.name) LIKE LOWER(CONCAT( '%', cast(:watcherNameLike as string), '%')) ESCAPE '\\')
        AND (s.createdAt < :cursor
          OR (s.createdAt = :cursor AND s.id < :idAfter))
      ORDER BY s.createdAt DESC, s.id DESC
      """)
    List<WatchingSessionSnapshot> findByContentIdAfterDesc(
        @Param("contentId") UUID contentId,
        @Param("watcherNameLike") String watcherNameLike,
        @Param("now") Instant now,
        @Param("cursor") Instant cursor,
        @Param("idAfter") UUID idAfter,
        Pageable pageable
    );

    // 콘텐츠 기준 시청 세션 목록 - 첫 페이지, 오래된 순(오름차순)
    @Query("""
      SELECT s FROM WatchingSessionSnapshot s
      JOIN User u ON u.id = s.watcherId
      WHERE s.contentId = :contentId
        AND s.expiresAt > :now
        AND (cast(:watcherNameLike as string) IS NULL OR LOWER(u.name) LIKE LOWER(CONCAT('%', cast(:watcherNameLike as string), '%')) ESCAPE '\\')
      ORDER BY s.createdAt ASC, s.id ASC
      """)
    List<WatchingSessionSnapshot> findByContentIdFirstPageAsc(
        @Param("contentId") UUID contentId,
        @Param("watcherNameLike") String watcherNameLike,
        @Param("now") Instant now,
        Pageable pageable
    );

    // 콘텐츠 기준 시청 세션 목록 - 커서 이후, 오래된 순(오름차순)
    @Query("""
      SELECT s FROM WatchingSessionSnapshot s
      JOIN User u ON u.id = s.watcherId
      WHERE s.contentId = :contentId
        AND s.expiresAt > :now
        AND (cast(:watcherNameLike as string) IS NULL OR LOWER(u.name) LIKE LOWER(CONCAT('%', cast(:watcherNameLike as string), '%')) ESCAPE '\\')
        AND (s.createdAt > :cursor
          OR (s.createdAt = :cursor AND s.id > :idAfter))
      ORDER BY s.createdAt ASC, s.id ASC
      """)
    List<WatchingSessionSnapshot> findByContentIdAfterAsc(
        @Param("contentId") UUID contentId,
        @Param("watcherNameLike") String watcherNameLike,
        @Param("now") Instant now,
        @Param("cursor") Instant cursor,
        @Param("idAfter") UUID idAfter,
        Pageable pageable
    );

    // 콘텐츠 기준 활성 세션 총 개수 (totalCount)
    @Query("""
      SELECT COUNT(s) FROM WatchingSessionSnapshot s
      JOIN User u ON u.id = s.watcherId
      WHERE s.contentId = :contentId
        AND s.expiresAt > :now
        AND (cast(:watcherNameLike as string) IS NULL OR LOWER(u.name) LIKE LOWER(CONCAT('%', cast(:watcherNameLike as string), '%')) ESCAPE '\\')
     """)
    long countByContentId(
        @Param("contentId") UUID contentId,
        @Param("watcherNameLike") String watcherNameLike,
        @Param("now") Instant now
    );

    // 아직 만료되지 않은 활성 세션의 expiresAt만 연장 (heartbeat 갱신용)
    // 벌크 업데이트라 JPA auditing을 거치지 않아 updatedAt은 변경되지 않음 (마지막 heartbeat 시각으로 오염되지 않는 편이 스냅샷에 맞음)
    // 리턴값: 갱신된 행 수 (0이면 활성 세션 없음 -> heartbeat 무효)
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE WatchingSessionSnapshot s SET s.expiresAt = :newExpiresAt "
    + "WHERE s.watcherId = :watcherId AND s.contentId = :contentId AND s.expiresAt > :now")
    int renewExpiresAt(
        @Param("watcherId") UUID watcherId,
        @Param("contentId") UUID contentId,
        @Param("now") Instant now,
        @Param("newExpiresAt") Instant newExpiresAt
    );

    // 여러 콘텐츠의 실시간 시청자 수를 한 번에 집계한다 (목록 페이지의 N+1 방지용).
    // 시청 세션이 하나도 없는 콘텐츠는 결과에 포함되지 않으므로, 호출부에서 0으로 기본값 처리해야 함.
    @Query("""
        SELECT s.contentId AS contentId, COUNT(s) AS watcherCount
        FROM WatchingSessionSnapshot s
        WHERE s.contentId IN :contentIds AND s.expiresAt > :now
        GROUP BY s.contentId
        """)
    List<ContentWatcherCountView> countGroupedByContentIds(
            @Param("contentIds") Collection<UUID> contentIds, @Param("now") Instant now);

}
