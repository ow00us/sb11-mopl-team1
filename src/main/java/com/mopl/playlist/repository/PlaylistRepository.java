package com.mopl.playlist.repository;

import com.mopl.playlist.entity.Playlist;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface PlaylistRepository extends JpaRepository<Playlist, UUID> {

    @Query("SELECT p FROM Playlist p ORDER BY p.updatedAt ASC, p.id ASC LIMIT :limit")
    List<Playlist> findByUpdatedAtAsc(
            @Param("keywordLike") String keywordLike,
            @Param("ownerIdEqual") String ownerIdEqual,
            @Param("cursorTime") Instant cursorTime,
            @Param("idAfter") String idAfter,
            @Param("limit") int limit
    );

    @Query("SELECT p FROM Playlist p ORDER BY p.updatedAt DESC, p.id ASC LIMIT :limit")
    List<Playlist> findByUpdatedAtDesc(
            @Param("keywordLike") String keywordLike,
            @Param("ownerIdEqual") String ownerIdEqual,
            @Param("cursorTime") Instant cursorTime,
            @Param("idAfter") String idAfter,
            @Param("limit") int limit
    );

    @Query("SELECT p FROM Playlist p ORDER BY p.subscriberCount ASC, p.id ASC LIMIT :limit")
    List<Playlist> findBySubscriberCountAsc(
            @Param("keywordLike") String keywordLike,
            @Param("ownerIdEqual") String ownerIdEqual,
            @Param("cursorCount") Long cursorCount,
            @Param("idAfter") String idAfter,
            @Param("limit") int limit
    );

    @Query("SELECT p FROM Playlist p ORDER BY p.subscriberCount DESC, p.id ASC LIMIT :limit")
    List<Playlist> findBySubscriberCountDesc(
            @Param("keywordLike") String keywordLike,
            @Param("ownerIdEqual") String ownerIdEqual,
            @Param("cursorCount") Long cursorCount,
            @Param("idAfter") String idAfter,
            @Param("limit") int limit
    );
}