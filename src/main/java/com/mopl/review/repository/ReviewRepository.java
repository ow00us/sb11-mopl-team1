package com.mopl.review.repository;

import com.mopl.review.entity.Review;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ReviewRepository extends JpaRepository<Review, UUID> {

    boolean existsByAuthorIdAndContentId(UUID authorId, UUID contentId);

    Optional<Review> findByAuthorIdAndContentId(UUID authorId, UUID contentId);

    @Query(value = """
            SELECT COUNT(*) FROM reviews
            WHERE (CAST(:contentId AS uuid) IS NULL OR content_id = CAST(:contentId AS uuid))
            """, nativeQuery = true)
    long countByFilter(@Param("contentId") UUID contentId);

    @Query(value = """
            SELECT * FROM reviews
            WHERE (CAST(:contentId AS uuid) IS NULL OR content_id = CAST(:contentId AS uuid))
              AND (
                CAST(:cursorCreatedAt AS timestamptz) IS NULL
                OR created_at < CAST(:cursorCreatedAt AS timestamptz)
                OR (created_at = CAST(:cursorCreatedAt AS timestamptz) AND id > CAST(:idAfter AS uuid))
              )
            ORDER BY created_at DESC, id ASC
            LIMIT :limit
            """, nativeQuery = true)
    List<Review> findByCreatedAtDesc(
            @Param("contentId") UUID contentId,
            @Param("cursorCreatedAt") Instant cursorCreatedAt,
            @Param("idAfter") UUID idAfter,
            @Param("limit") int limit);

    @Query(value = """
            SELECT * FROM reviews
            WHERE (CAST(:contentId AS uuid) IS NULL OR content_id = CAST(:contentId AS uuid))
              AND (
                CAST(:cursorCreatedAt AS timestamptz) IS NULL
                OR created_at > CAST(:cursorCreatedAt AS timestamptz)
                OR (created_at = CAST(:cursorCreatedAt AS timestamptz) AND id > CAST(:idAfter AS uuid))
              )
            ORDER BY created_at ASC, id ASC
            LIMIT :limit
            """, nativeQuery = true)
    List<Review> findByCreatedAtAsc(
            @Param("contentId") UUID contentId,
            @Param("cursorCreatedAt") Instant cursorCreatedAt,
            @Param("idAfter") UUID idAfter,
            @Param("limit") int limit);

    @Query(value = """
            SELECT * FROM reviews
            WHERE (CAST(:contentId AS uuid) IS NULL OR content_id = CAST(:contentId AS uuid))
              AND (
                CAST(:cursorRating AS numeric) IS NULL
                OR rating < CAST(:cursorRating AS numeric)
                OR (rating = CAST(:cursorRating AS numeric) AND id > CAST(:idAfter AS uuid))
              )
            ORDER BY rating DESC, id ASC
            LIMIT :limit
            """, nativeQuery = true)
    List<Review> findByRatingDesc(
            @Param("contentId") UUID contentId,
            @Param("cursorRating") BigDecimal cursorRating,
            @Param("idAfter") UUID idAfter,
            @Param("limit") int limit);

    @Query(value = """
            SELECT * FROM reviews
            WHERE (CAST(:contentId AS uuid) IS NULL OR content_id = CAST(:contentId AS uuid))
              AND (
                CAST(:cursorRating AS numeric) IS NULL
                OR rating > CAST(:cursorRating AS numeric)
                OR (rating = CAST(:cursorRating AS numeric) AND id > CAST(:idAfter AS uuid))
              )
            ORDER BY rating ASC, id ASC
            LIMIT :limit
            """, nativeQuery = true)
    List<Review> findByRatingAsc(
            @Param("contentId") UUID contentId,
            @Param("cursorRating") BigDecimal cursorRating,
            @Param("idAfter") UUID idAfter,
            @Param("limit") int limit);
}