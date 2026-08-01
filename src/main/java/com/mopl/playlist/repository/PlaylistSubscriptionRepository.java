package com.mopl.playlist.repository;

import com.mopl.playlist.entity.PlaylistSubscription;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public interface PlaylistSubscriptionRepository extends JpaRepository<PlaylistSubscription, UUID> {

    Optional<PlaylistSubscription> findByPlaylistIdAndSubscriberId(UUID playlistId, UUID subscriberId);

    boolean existsByPlaylistIdAndSubscriberId(UUID playlistId, UUID subscriberId);

    long countByPlaylistId(UUID playlistId);

    @Query("SELECT ps.playlistId FROM PlaylistSubscription ps WHERE ps.subscriberId = :subscriberId AND ps.playlistId IN :playlistIds")
    Set<UUID> findSubscribedPlaylistIds(@Param("subscriberId") UUID subscriberId, @Param("playlistIds") List<UUID> playlistIds);

    // Red 스텁: Green 단계에서 실제 커서 쿼리로 교체한다.
    @Query(value = "SELECT * FROM playlist_subscriptions WHERE false", nativeQuery = true)
    List<PlaylistSubscription> findByPlaylistIdDesc(
            @Param("playlistId") String playlistId,
            @Param("cursorTime") Instant cursorTime,
            @Param("idAfter") String idAfter,
            @Param("limit") int limit
    );
}