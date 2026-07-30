package com.mopl.watchingsession.repository;

import com.mopl.watchingsession.entity.WatchingSessionSnapshot;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
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
        AND (:watcherNameLike IS NULL OR LOWER(u.name) LIKE LOWER(CONCAT('%', :watcherNameLike, '%')) ESCAPE '\\')
      ORDER BY s.updatedAt DESC, s.id DESC
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
        AND (:watcherNameLike IS NULL OR LOWER(u.name) LIKE LOWER(CONCAT( '%', :watcherNameLike, '%')) ESCAPE '\\')
        AND (s.updatedAt < :cursor
          OR (s.updatedAt = :cursor AND s.id < :idAfter))
      ORDER BY s.updatedAt DESC, s.id DESC
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
        AND (:watcherNameLike IS NULL OR LOWER(u.name) LIKE LOWER(CONCAT('%', :watcherNameLike, '%')) ESCAPE '\\')
      ORDER BY s.updatedAt ASC, s.id ASC
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
        AND (:watcherNameLike IS NULL OR LOWER(u.name) LIKE LOWER(CONCAT('%', :watcherNameLike, '%')) ESCAPE '\\')
        AND (s.updatedAt > :cursor
          OR (s.updatedAt = :cursor AND s.id > :idAfter))
      ORDER BY s.updatedAt ASC, s.id ASC
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
        AND (:watcherNameLike IS NULL OR LOWER(u.name) LIKE LOWER(CONCAT('%', :watcherNameLike, '%')) ESCAPE '\\')
     """)
    long countByContentId(
        @Param("contentId") UUID contentId,
        @Param("watcherNameLike") String watcherNameLike,
        @Param("now") Instant now
    );



}
