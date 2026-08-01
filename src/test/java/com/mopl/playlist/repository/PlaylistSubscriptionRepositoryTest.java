package com.mopl.playlist.repository;

import com.mopl.global.config.JpaConfig;
import com.mopl.playlist.entity.Playlist;
import com.mopl.playlist.entity.PlaylistSubscription;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@ActiveProfiles("test")
@Import(JpaConfig.class)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
class PlaylistSubscriptionRepositoryTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    @Autowired TestEntityManager em;
    @Autowired PlaylistSubscriptionRepository subscriptionRepository;

    private static final UUID OWNER    = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final UUID SUB_B    = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
    private static final UUID SUB_C    = UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc");
    private static final UUID SUB_D    = UUID.fromString("dddddddd-dddd-dddd-dddd-dddddddddddd");

    @BeforeEach
    void insertUsers() {
        em.getEntityManager().createNativeQuery("""
                INSERT INTO users (id, created_at, updated_at, email, name, password_hash, role, locked)
                VALUES
                  (:o, NOW(), NOW(), 'o@test.com', 'O', 'hash', 'USER', false),
                  (:b, NOW(), NOW(), 'b@test.com', 'B', 'hash', 'USER', false),
                  (:c, NOW(), NOW(), 'c@test.com', 'C', 'hash', 'USER', false),
                  (:d, NOW(), NOW(), 'd@test.com', 'D', 'hash', 'USER', false)
                ON CONFLICT DO NOTHING
                """)
                .setParameter("o", OWNER)
                .setParameter("b", SUB_B)
                .setParameter("c", SUB_C)
                .setParameter("d", SUB_D)
                .executeUpdate();
        em.flush();
    }

    // ── countByPlaylistId ─────────────────────────────────────────────────────

    @Test
    @DisplayName("countByPlaylistId 는 해당 플레이리스트 구독 수만 반환한다")
    void countByPlaylistId_success() {
        UUID pl1 = persistPlaylist("PL1").getId();
        UUID pl2 = persistPlaylist("PL2").getId();

        persistSubscription(pl1, SUB_B, Instant.now());
        persistSubscription(pl1, SUB_C, Instant.now());
        persistSubscription(pl2, SUB_D, Instant.now());  // 다른 플레이리스트 구독은 미포함

        assertThat(subscriptionRepository.countByPlaylistId(pl1)).isEqualTo(2L);
        assertThat(subscriptionRepository.countByPlaylistId(pl2)).isEqualTo(1L);
    }

    // ── findByPlaylistIdDesc ──────────────────────────────────────────────────

    @Test
    @DisplayName("findByPlaylistIdDesc 는 해당 플레이리스트 구독을 createdAt DESC 로 반환한다")
    void findByPlaylistIdDesc_orderDesc() {
        UUID playlistId = persistPlaylist("PL").getId();
        Instant t1 = Instant.parse("2026-08-01T10:00:00Z");
        Instant t2 = Instant.parse("2026-08-01T11:00:00Z");
        Instant t3 = Instant.parse("2026-08-01T12:00:00Z");

        persistSubscription(playlistId, SUB_B, t1);
        persistSubscription(playlistId, SUB_C, t3);  // 가장 최근
        persistSubscription(playlistId, SUB_D, t2);

        List<PlaylistSubscription> result = subscriptionRepository.findByPlaylistIdDesc(
                playlistId.toString(), null, null, 10);

        assertThat(result).extracting(PlaylistSubscription::getSubscriberId)
                .containsExactly(SUB_C, SUB_D, SUB_B);
    }

    @Test
    @DisplayName("findByPlaylistIdDesc 는 cursor+idAfter 로 다음 페이지를 반환한다")
    void findByPlaylistIdDesc_cursorPagination() {
        UUID playlistId = persistPlaylist("PL").getId();
        Instant base = Instant.parse("2026-08-01T10:00:00Z");
        PlaylistSubscription s1 = persistSubscription(playlistId, SUB_B, base);
        PlaylistSubscription s2 = persistSubscription(playlistId, SUB_C, base);              // 같은 시각 → id 타이브레이커
        PlaylistSubscription s3 = persistSubscription(playlistId, SUB_D, base.plusSeconds(60));

        List<PlaylistSubscription> page1 = subscriptionRepository.findByPlaylistIdDesc(
                playlistId.toString(), null, null, 1);
        assertThat(page1).extracting(PlaylistSubscription::getId).containsExactly(s3.getId());

        PlaylistSubscription last1 = page1.get(0);
        List<PlaylistSubscription> page2 = subscriptionRepository.findByPlaylistIdDesc(
                playlistId.toString(), last1.getCreatedAt(), last1.getId().toString(), 10);

        UUID smaller = s1.getId().compareTo(s2.getId()) < 0 ? s1.getId() : s2.getId();
        UUID larger  = s1.getId().compareTo(s2.getId()) < 0 ? s2.getId() : s1.getId();
        assertThat(page2).extracting(PlaylistSubscription::getId).containsExactly(smaller, larger);
    }

    // ── 헬퍼 ─────────────────────────────────────────────────────────────────

    private Playlist persistPlaylist(String title) {
        Playlist p = Playlist.builder().ownerId(OWNER).title(title).description("설명").build();
        em.persist(p);
        em.flush();
        return p;
    }

    private PlaylistSubscription persistSubscription(UUID playlistId, UUID subscriberId, Instant createdAt) {
        PlaylistSubscription sub = PlaylistSubscription.builder()
                .playlistId(playlistId).subscriberId(subscriberId).build();
        em.persist(sub);
        em.flush();
        // JPA auditing 이 setField 후 merge 시 created_at 을 덮어쓰므로 native UPDATE 로 강제한다.
        em.getEntityManager().createNativeQuery("""
                UPDATE playlist_subscriptions SET created_at = :ts WHERE id = :id
                """)
                .setParameter("ts", createdAt)
                .setParameter("id", sub.getId())
                .executeUpdate();
        em.flush();
        em.clear();
        PlaylistSubscription refreshed = em.find(PlaylistSubscription.class, sub.getId());
        return refreshed != null ? refreshed : sub;
    }
}