package com.mopl.playlist.service;

import com.mopl.content.repository.ContentRepository;
import com.mopl.global.config.JpaConfig;
import com.mopl.playlist.entity.Playlist;
import com.mopl.playlist.entity.PlaylistContent;
import com.mopl.playlist.repository.PlaylistContentRepository;
import com.mopl.playlist.repository.PlaylistRepository;
import com.mopl.playlist.repository.PlaylistSubscriptionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

// REQUIRES_NEW로 인해 상위 트랜잭션이 rollback-only로 오염되지 않는지 실제 Spring proxy + DB로 검증한다.
@DataJpaTest
@ActiveProfiles("test")
@Import({JpaConfig.class, PlaylistContentSaver.class, PlaylistServiceImpl.class})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class PlaylistContentSaverIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    @Autowired PlaylistService playlistService;
    @Autowired PlaylistContentSaver saver;
    @Autowired PlaylistContentRepository playlistContentRepository;
    @Autowired PlaylistRepository playlistRepository;
    @Autowired PlatformTransactionManager txManager;
    @Autowired JdbcTemplate jdbcTemplate;

    @SuppressWarnings("unused") @Autowired PlaylistSubscriptionRepository subscriptionRepository;
    @SuppressWarnings("unused") @Autowired ContentRepository contentRepository;

    private static final UUID OWNER_ID     = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final UUID CONTENT_ID_1 = UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc");
    private static final UUID CONTENT_ID_2 = UUID.fromString("dddddddd-dddd-dddd-dddd-dddddddddddd");

    private Playlist playlist;

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("DELETE FROM playlist_contents");
        jdbcTemplate.update("DELETE FROM playlists");
        jdbcTemplate.update("DELETE FROM contents");
        jdbcTemplate.update("DELETE FROM users");

        Instant now = Instant.now();
        jdbcTemplate.update(
                "INSERT INTO users (id, created_at, updated_at, email, password_hash, name, role) VALUES (?, ?, ?, ?, ?, ?, ?)",
                OWNER_ID, Timestamp.from(now), Timestamp.from(now),
                "owner@test.com", "hash", "owner", "USER"
        );
        for (UUID cid : new UUID[]{CONTENT_ID_1, CONTENT_ID_2}) {
            jdbcTemplate.update(
                    "INSERT INTO contents (id, created_at, updated_at, title, description, type, average_rating, review_count, watcher_count) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    cid, Timestamp.from(now), Timestamp.from(now), "콘텐츠", "설명", "MOVIE", 0.0, 0, 0
            );
        }
        playlist = playlistRepository.saveAndFlush(
                Playlist.builder().ownerId(OWNER_ID).title("테스트").description("설명").build()
        );
    }

    @Test
    @DisplayName("race 시뮬레이션: pre-check 통과 후 다른 요청이 먼저 저장한 상태에서 저장 시도해도 상위 트랜잭션은 정상 커밋된다")
    void requiresNewSaver_doesNotPoisonParentTransaction() {
        // given: (playlist, content1)이 이미 저장된 상태
        playlistContentRepository.saveAndFlush(PlaylistContent.create(playlist.getId(), CONTENT_ID_1));

        TransactionTemplate outer = new TransactionTemplate(txManager);

        // when: outer 트랜잭션 안에서 (1) 중복 저장 시도 (2) 정상 저장 수행
        assertThatCode(() -> outer.execute(status -> {
            try {
                saver.save(playlist.getId(), CONTENT_ID_1);
            } catch (DataIntegrityViolationException ignored) {
                // race 시뮬레이션: 상위 서비스가 duplicate 판별 후 무시하는 것과 동일
            }
            playlistContentRepository.saveAndFlush(PlaylistContent.create(playlist.getId(), CONTENT_ID_2));
            return null;
        })).doesNotThrowAnyException();

        // then: outer가 정상 커밋되어 CONTENT_ID_2도 저장됨
        assertThat(playlistContentRepository
                .existsByPlaylistIdAndContentId(playlist.getId(), CONTENT_ID_2)).isTrue();
        assertThat(playlistContentRepository
                .existsByPlaylistIdAndContentId(playlist.getId(), CONTENT_ID_1)).isTrue();
    }

    @Test
    @DisplayName("서비스 addContent를 통한 정상 저장은 그대로 반영된다")
    void addContent_normalPath_saves() {
        playlistService.addContent(playlist.getId(), CONTENT_ID_1, OWNER_ID);

        assertThat(playlistContentRepository
                .existsByPlaylistIdAndContentId(playlist.getId(), CONTENT_ID_1)).isTrue();
    }
}