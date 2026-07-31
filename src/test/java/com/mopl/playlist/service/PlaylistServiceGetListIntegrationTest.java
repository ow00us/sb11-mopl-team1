package com.mopl.playlist.service;

import com.mopl.content.repository.ContentRepository;
import com.mopl.global.common.CursorResponse;
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

// getList가 실제 DB에서 페이지 크기·태그 개수와 무관하게 상수 쿼리로 완료되는지 Hibernate Statistics로 고정한다.
@DataJpaTest
@ActiveProfiles("test")
@Import({JpaConfig.class, PlaylistContentSaver.class, PlaylistServiceImpl.class})
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
    @DisplayName("getList는 페이지 크기·콘텐츠 수·태그 수와 무관하게 콘텐츠 로딩 SQL이 상수(playlist_contents 1 + contents+tags 1)로 완료된다")
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
        // → 4쿼리 상한 (limit·contents·tags 개수에 비례하지 않음)
        assertThat(queryCount)
                .as("페이지 크기·콘텐츠 수·태그 수와 무관하게 상수 SQL로 완료되어야 한다")
                .isLessThanOrEqualTo(4);

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