package com.mopl.playlist.service;

import com.mopl.content.repository.ContentRepository;
import com.mopl.global.common.ContentSummary;
import com.mopl.global.config.JpaConfig;
import com.mopl.playlist.dto.PlaylistDto;
import com.mopl.playlist.entity.Playlist;
import com.mopl.playlist.entity.PlaylistContent;
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

// 단건 조회 get(playlistId, requesterId)이 콘텐츠·태그 개수와 무관하게 상수 SQL로 완료되는지 고정한다.
// loadContentsBatch(목록 조회 경로)와 loadContents(단건 조회 경로)의 태그 로딩 방식을 통일하는 회귀 방지 테스트.
@DataJpaTest
@ActiveProfiles("test")
@Import({JpaConfig.class, PlaylistContentSaver.class, PlaylistServiceImpl.class})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class PlaylistServiceGetIntegrationTest {

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
    @DisplayName("단건 조회는 콘텐츠 수·태그 수와 무관하게 콘텐츠 로딩 SQL이 상수(playlist 1 + playlist_contents 1 + contents+tags 1)로 완료된다")
    void get_batchQueries_areConstant() {
        int contentsPerPlaylist = 5;
        int tagsPerContent = 3;

        List<UUID> contentIds = seedContentsWithTags(contentsPerPlaylist, tagsPerContent);
        Playlist playlist = seedPlaylist();
        seedLinks(playlist, contentIds);

        Statistics statistics = getStatistics();
        statistics.clear();

        // requesterId = null 로 subscription exists 쿼리를 배제하고 순수 조회 경로만 측정
        PlaylistDto result = playlistService.get(playlist.getId(), null);

        long queryCount = statistics.getPrepareStatementCount();

        // 예상 SQL:
        //  1) playlist 단건 조회 (findOrThrow)
        //  2) playlist_contents 조회 (loadContents)
        //  3) contents + content_tags EntityGraph 조인 조회
        // → 3쿼리 상한 (콘텐츠·태그 개수에 비례하지 않음)
        assertThat(queryCount)
                .as("콘텐츠 수·태그 수와 무관하게 상수 SQL로 완료되어야 한다 (실제 %d)", queryCount)
                .isLessThanOrEqualTo(3);

        assertThat(result.contents()).hasSize(contentsPerPlaylist);
        assertThat(result.contents())
                .allSatisfy(summary -> assertThat(summary.tags()).hasSize(tagsPerContent));
    }

    @Test
    @DisplayName("콘텐츠 수가 늘어도 단건 조회 SQL은 상수로 유지된다")
    void get_queryCount_doesNotGrowWithContentCount() {
        Playlist small = seedPlaylist();
        List<UUID> smallContentIds = seedContentsWithTags(2, 2);
        seedLinks(small, smallContentIds);

        Playlist large = seedPlaylist();
        List<UUID> largeContentIds = seedContentsWithTags(20, 2);
        seedLinks(large, largeContentIds);

        Statistics statistics = getStatistics();

        statistics.clear();
        playlistService.get(small.getId(), null);
        long smallQueries = statistics.getPrepareStatementCount();

        statistics.clear();
        playlistService.get(large.getId(), null);
        long largeQueries = statistics.getPrepareStatementCount();

        assertThat(largeQueries)
                .as("콘텐츠 10배 증가에도 SQL 수는 동일해야 한다 (small=%d, large=%d)", smallQueries, largeQueries)
                .isEqualTo(smallQueries);
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
                    "콘텐츠" + UUID.randomUUID(), "설명", "MOVIE", 0.0, 0, 0
            );
            for (int t = 0; t < tagsPerContent; t++) {
                jdbcTemplate.update(
                        "INSERT INTO content_tags (content_id, tag) VALUES (?, ?)",
                        id, "tag-" + id + "-" + t
                );
            }
        }
        return ids;
    }

    private Playlist seedPlaylist() {
        return playlistRepository.saveAndFlush(
                Playlist.builder().ownerId(OWNER_ID).title("P").description("설명").build()
        );
    }

    private void seedLinks(Playlist playlist, List<UUID> contentIds) {
        for (UUID cid : contentIds) {
            playlistContentRepository.saveAndFlush(PlaylistContent.create(playlist.getId(), cid));
        }
    }
}