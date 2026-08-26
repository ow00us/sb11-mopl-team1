package com.mopl.playlist.service;

import com.mopl.content.repository.ContentRepository;
import com.mopl.global.common.CursorResponse;
import com.mopl.global.config.JpaConfig;
import com.mopl.global.outbox.OutboxRecorderImpl;
import com.mopl.playlist.dto.PlaylistDto;
import com.mopl.playlist.entity.Playlist;
import com.mopl.playlist.entity.PlaylistContent;
import com.mopl.playlist.event.PlaylistSubscriptionEventFactory;
import com.mopl.playlist.repository.PlaylistContentRepository;
import com.mopl.playlist.repository.PlaylistRepository;
import com.mopl.playlist.repository.PlaylistSubscriptionRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

// getList가 실제 DB에서 페이지 크기·태그 개수와 무관하게 상수 쿼리로 완료되는지 Hibernate Statistics로 고정한다.
@DataJpaTest
@ActiveProfiles("test")
@Import({
        JpaConfig.class,
        PlaylistContentSaver.class,
        PlaylistServiceImpl.class,
        OutboxRecorderImpl.class,
        PlaylistSubscriptionEventFactory.class,
        PlaylistIntegrationTestConfig.class,
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class PlaylistServiceGetListIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    @Autowired PlaylistService playlistService;
    @Autowired PlaylistRepository playlistRepository;
    @Autowired PlaylistContentRepository playlistContentRepository;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired EntityManagerFactory entityManagerFactory;

    @SuppressWarnings("unused") @Autowired PlaylistSubscriptionRepository subscriptionRepository;
    @SuppressWarnings("unused") @Autowired ContentRepository contentRepository;
    @SuppressWarnings("unused") @Autowired EntityManager entityManager;

    private static final UUID OWNER_ID = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("DELETE FROM playlist_contents");
        jdbcTemplate.update("DELETE FROM playlist_subscriptions");
        jdbcTemplate.update("DELETE FROM playlists");
        jdbcTemplate.update("DELETE FROM content_tags");
        jdbcTemplate.update("DELETE FROM contents");
        jdbcTemplate.update("DELETE FROM users");

        Instant now = Instant.now();
        jdbcTemplate.update(
                "INSERT INTO users (id, created_at, updated_at, email, password_hash, name, role) VALUES (?, ?, ?, ?, ?, ?, ?)",
                OWNER_ID, Timestamp.from(now), Timestamp.from(now),
                "owner@test.com", "hash", "owner", "USER"
        );
    }

    @Test
    @DisplayName("getList는 페이지 크기·콘텐츠 수·태그 수와 무관하게 콘텐츠·owner 로딩 SQL이 상수(playlist_contents 1 + contents+tags 1 + users 1)로 완료된다")
    void getList_batchQueries_areConstant() {
        int playlistCount = 5;
        int contentsPerPlaylist = 3;
        int tagsPerContent = 4;

        List<UUID> contentIds = seedContentsWithTags(playlistCount * contentsPerPlaylist, tagsPerContent);
        List<Playlist> playlists = seedPlaylists(playlistCount);
        seedLinks(playlists, contentIds, contentsPerPlaylist);

        Statistics statistics = getStatistics();
        statistics.clear();

        CursorResponse<PlaylistDto> result = playlistService.getList(
                null, null, null, null, null, playlistCount, "updatedAt", "ASCENDING", null);

        long queryCount = statistics.getPrepareStatementCount();

        // 예상 SQL:
        //  1) playlist 페이지 조회
        //  2) countByFilter
        //  3) playlist_contents 배치 조회
        //  4) contents + content_tags EntityGraph 조인 조회
        //  5) users 배치 조회 (toOwnerSummaryMap, distinct ownerIds)
        // → 5쿼리 상한 (limit·contents·tags 개수에 비례하지 않음)
        assertThat(queryCount)
                .as("페이지 크기·콘텐츠 수·태그 수와 무관하게 상수 SQL로 완료되어야 한다")
                .isLessThanOrEqualTo(5);

        assertThat(result.data()).hasSize(playlistCount);
        assertThat(result.data().get(0).contents()).hasSize(contentsPerPlaylist);
    }

    @Test
    @DisplayName("페이지 크기가 커져도 콘텐츠 로딩 SQL은 상수로 유지된다")
    void getList_queryCount_doesNotGrowWithPageSize() {
        int contentsPerPlaylist = 2;
        List<UUID> contentIds = seedContentsWithTags(20 * contentsPerPlaylist, 2);
        List<Playlist> playlists = seedPlaylists(20);
        seedLinks(playlists, contentIds, contentsPerPlaylist);

        Statistics statistics = getStatistics();

        statistics.clear();
        playlistService.getList(null, null, null, null, null, 5, "updatedAt", "ASCENDING", null);
        long smallPageQueries = statistics.getPrepareStatementCount();

        statistics.clear();
        playlistService.getList(null, null, null, null, null, 20, "updatedAt", "ASCENDING", null);
        long largePageQueries = statistics.getPrepareStatementCount();

        assertThat(largePageQueries)
                .as("페이지 크기 4배 증가에도 SQL 수는 동일해야 한다")
                .isEqualTo(smallPageQueries);
    }

    // ── Phase C: sortBy=subscriberCount 정렬·커서 분기 커버 ─────────────────

    @Test
    @DisplayName("sortBy=subscriberCount ASCENDING 정렬 시 subscriber_count 오름차순으로 반환된다")
    void getList_sortBySubscriberCount_asc_returnsAscendingOrder() {
        List<Playlist> playlists = seedPlaylists(3);
        setSubscriberCount(playlists.get(0).getId(), 5);
        setSubscriberCount(playlists.get(1).getId(), 1);
        setSubscriberCount(playlists.get(2).getId(), 3);

        CursorResponse<PlaylistDto> result = playlistService.getList(
                null, null, null, null, null, 10, "subscriberCount", "ASCENDING", null);

        assertThat(result.data()).hasSize(3);
        assertThat(result.data()).extracting(PlaylistDto::subscriberCount)
                .containsExactly(1L, 3L, 5L);
    }

    @Test
    @DisplayName("sortBy=subscriberCount DESCENDING 정렬 시 subscriber_count 내림차순으로 반환된다")
    void getList_sortBySubscriberCount_desc_returnsDescendingOrder() {
        List<Playlist> playlists = seedPlaylists(3);
        setSubscriberCount(playlists.get(0).getId(), 5);
        setSubscriberCount(playlists.get(1).getId(), 1);
        setSubscriberCount(playlists.get(2).getId(), 3);

        CursorResponse<PlaylistDto> result = playlistService.getList(
                null, null, null, null, null, 10, "subscriberCount", "DESCENDING", null);

        assertThat(result.data()).extracting(PlaylistDto::subscriberCount)
                .containsExactly(5L, 3L, 1L);
    }

    @Test
    @DisplayName("sortBy=subscriberCount ASC + 커서 페이지네이션 시 nextCursor 로 다음 페이지 이어받는다")
    void getList_sortBySubscriberCount_asc_withCursor_paginatesCorrectly() {
        List<Playlist> playlists = seedPlaylists(4);
        setSubscriberCount(playlists.get(0).getId(), 1);
        setSubscriberCount(playlists.get(1).getId(), 2);
        setSubscriberCount(playlists.get(2).getId(), 3);
        setSubscriberCount(playlists.get(3).getId(), 4);

        // 첫 페이지 2건 조회
        CursorResponse<PlaylistDto> firstPage = playlistService.getList(
                null, null, null, null, null, 2, "subscriberCount", "ASCENDING", null);
        assertThat(firstPage.data()).extracting(PlaylistDto::subscriberCount)
                .containsExactly(1L, 2L);
        assertThat(firstPage.hasNext()).isTrue();
        assertThat(firstPage.nextCursor()).isNotNull();
        assertThat(firstPage.nextIdAfter()).isNotNull();

        // nextCursor 로 다음 페이지 조회
        CursorResponse<PlaylistDto> secondPage = playlistService.getList(
                null, null, null, firstPage.nextCursor(), firstPage.nextIdAfter(),
                2, "subscriberCount", "ASCENDING", null);
        assertThat(secondPage.data()).extracting(PlaylistDto::subscriberCount)
                .containsExactly(3L, 4L);
        assertThat(secondPage.hasNext()).isFalse();
    }

    @Test
    @DisplayName("sortBy=updatedAt DESCENDING + 커서 페이지네이션 시 페이지 간 중복 없이 DESC 순서로 이어받는다")
    void getList_sortByUpdatedAt_desc_withCursor_paginatesCorrectly() {
        // 명시적 updatedAt 값으로 결정적 정렬 검증
        List<Playlist> playlists = seedPlaylists(4);
        Instant base = Instant.parse("2026-08-01T00:00:00Z");
        setUpdatedAt(playlists.get(0).getId(), base);                    // 가장 오래됨
        setUpdatedAt(playlists.get(1).getId(), base.plusSeconds(100));
        setUpdatedAt(playlists.get(2).getId(), base.plusSeconds(200));
        setUpdatedAt(playlists.get(3).getId(), base.plusSeconds(300));   // 가장 최신

        // 첫 페이지: 최신 2건 (updatedAt DESC)
        CursorResponse<PlaylistDto> firstPage = playlistService.getList(
                null, null, null, null, null, 2, "updatedAt", "DESCENDING", null);
        assertThat(firstPage.data()).hasSize(2);
        assertThat(firstPage.hasNext()).isTrue();
        assertThat(firstPage.nextCursor()).isNotNull();
        assertThat(firstPage.nextIdAfter()).isNotNull();
        // 첫 페이지 안에서 DESC 정렬 유지
        assertThat(firstPage.data().get(0).updatedAt())
                .isAfterOrEqualTo(firstPage.data().get(1).updatedAt());

        // 두 번째 페이지: nextCursor 로 이어받아 나머지 2건
        CursorResponse<PlaylistDto> secondPage = playlistService.getList(
                null, null, null, firstPage.nextCursor(), firstPage.nextIdAfter(),
                2, "updatedAt", "DESCENDING", null);
        assertThat(secondPage.data()).hasSize(2);
        assertThat(secondPage.hasNext()).isFalse();
        // 두 번째 페이지 안에서 DESC 정렬 유지
        assertThat(secondPage.data().get(0).updatedAt())
                .isAfterOrEqualTo(secondPage.data().get(1).updatedAt());

        // 페이지 간 식별자 중복 없음
        List<UUID> firstIds = firstPage.data().stream().map(PlaylistDto::id).toList();
        List<UUID> secondIds = secondPage.data().stream().map(PlaylistDto::id).toList();
        assertThat(firstIds).doesNotContainAnyElementsOf(secondIds);

        // 전체 결과가 DESC 순서 유지 (첫 페이지 마지막 >= 두 번째 페이지 첫)
        assertThat(firstPage.data().get(1).updatedAt())
                .isAfterOrEqualTo(secondPage.data().get(0).updatedAt());
    }

    private void setSubscriberCount(UUID playlistId, long count) {
        jdbcTemplate.update("UPDATE playlists SET subscriber_count = ? WHERE id = ?", count, playlistId);
    }

    private void setUpdatedAt(UUID playlistId, Instant updatedAt) {
        jdbcTemplate.update("UPDATE playlists SET updated_at = ? WHERE id = ?",
                Timestamp.from(updatedAt), playlistId);
    }

    // ── 헬퍼 ──────────────────────────────────────────────────────────────────

    private Statistics getStatistics() {
        SessionFactory sessionFactory = entityManagerFactory.unwrap(SessionFactory.class);
        Statistics statistics = sessionFactory.getStatistics();
        statistics.setStatisticsEnabled(true);
        return statistics;
    }

    private List<UUID> seedContentsWithTags(int contentCount, int tagsPerContent) {
        Instant now = Instant.now();
        java.util.List<UUID> ids = new java.util.ArrayList<>(contentCount);
        for (int i = 0; i < contentCount; i++) {
            UUID id = UUID.randomUUID();
            ids.add(id);
            jdbcTemplate.update(
                    "INSERT INTO contents (id, created_at, updated_at, title, description, type, average_rating, review_count, watcher_count) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    id, Timestamp.from(now), Timestamp.from(now),
                    "콘텐츠" + i, "설명", "MOVIE", 0.0, 0, 0
            );
            for (int t = 0; t < tagsPerContent; t++) {
                jdbcTemplate.update(
                        "INSERT INTO content_tags (content_id, tag) VALUES (?, ?)",
                        id, "tag-" + i + "-" + t
                );
            }
        }
        return ids;
    }

    private List<Playlist> seedPlaylists(int count) {
        java.util.List<Playlist> result = new java.util.ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            Playlist p = playlistRepository.saveAndFlush(
                    Playlist.builder().ownerId(OWNER_ID).title("P" + i).description("설명" + i).build()
            );
            result.add(p);
        }
        return result;
    }

    private void seedLinks(List<Playlist> playlists, List<UUID> contentIds, int contentsPerPlaylist) {
        int cursor = 0;
        for (Playlist p : playlists) {
            for (int j = 0; j < contentsPerPlaylist; j++) {
                UUID cid = contentIds.get(cursor % contentIds.size());
                cursor++;
                playlistContentRepository.saveAndFlush(PlaylistContent.create(p.getId(), cid));
            }
        }
    }
}