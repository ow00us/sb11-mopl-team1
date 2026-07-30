package com.mopl.playlist.repository;

import com.mopl.global.config.JpaConfig;
import com.mopl.playlist.entity.Playlist;
import com.mopl.playlist.entity.PlaylistContent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest
@ActiveProfiles("test")
@Import(JpaConfig.class)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
class PlaylistContentRepositoryTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    @Autowired TestEntityManager em;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired PlaylistContentRepository playlistContentRepository;
    @Autowired PlaylistRepository playlistRepository;

    private static final UUID OWNER_ID  = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final UUID CONTENT_ID_1 = UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc");
    private static final UUID CONTENT_ID_2 = UUID.fromString("dddddddd-dddd-dddd-dddd-dddddddddddd");

    private Playlist playlist;

    @BeforeEach
    void setUp() {
        Instant now = Instant.now();
        jdbcTemplate.update(
                "INSERT INTO users (id, created_at, updated_at, email, password_hash, name, role) VALUES (?, ?, ?, ?, ?, ?, ?)",
                OWNER_ID, Timestamp.from(now), Timestamp.from(now),
                "owner@test.com", "hash", "owner", "USER"
        );
        jdbcTemplate.update(
                "INSERT INTO contents (id, created_at, updated_at, title, description, type, average_rating, review_count, watcher_count) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                CONTENT_ID_1, Timestamp.from(now), Timestamp.from(now), "콘텐츠1", "설명1", "MOVIE", 0.0, 0, 0
        );
        jdbcTemplate.update(
                "INSERT INTO contents (id, created_at, updated_at, title, description, type, average_rating, review_count, watcher_count) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                CONTENT_ID_2, Timestamp.from(now), Timestamp.from(now), "콘텐츠2", "설명2", "MOVIE", 0.0, 0, 0
        );
        playlist = playlistRepository.saveAndFlush(
                Playlist.builder().ownerId(OWNER_ID).title("테스트 플리").description("설명").build()
        );
    }

    @Test
    @DisplayName("콘텐츠를 플레이리스트에 추가하고 조회한다")
    void addAndFindContent() {
        PlaylistContent pc = playlistContentRepository.saveAndFlush(
                PlaylistContent.create(playlist.getId(), CONTENT_ID_1)
        );
        em.clear();

        assertThat(playlistContentRepository.existsByPlaylistIdAndContentId(playlist.getId(), CONTENT_ID_1)).isTrue();
        assertThat(pc.getPlaylistId()).isEqualTo(playlist.getId());
        assertThat(pc.getContentId()).isEqualTo(CONTENT_ID_1);
    }

    @Test
    @DisplayName("플레이리스트 ID로 콘텐츠 목록을 추가순으로 조회한다")
    void findAllByPlaylistId() {
        playlistContentRepository.saveAndFlush(PlaylistContent.create(playlist.getId(), CONTENT_ID_1));
        playlistContentRepository.saveAndFlush(PlaylistContent.create(playlist.getId(), CONTENT_ID_2));
        em.clear();

        List<PlaylistContent> contents = playlistContentRepository
                .findAllByPlaylistIdOrderByCreatedAtAsc(playlist.getId());

        assertThat(contents).hasSize(2);
        assertThat(contents.get(0).getContentId()).isEqualTo(CONTENT_ID_1);
        assertThat(contents.get(1).getContentId()).isEqualTo(CONTENT_ID_2);
    }

    @Test
    @DisplayName("동일 콘텐츠 중복 추가 시 DB 제약 위반이 발생한다")
    void duplicateContent_throwsException() {
        playlistContentRepository.saveAndFlush(PlaylistContent.create(playlist.getId(), CONTENT_ID_1));

        assertThatThrownBy(() ->
                playlistContentRepository.saveAndFlush(PlaylistContent.create(playlist.getId(), CONTENT_ID_1))
        ).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("플레이리스트에서 콘텐츠를 삭제한다")
    void deleteContent() {
        playlistContentRepository.saveAndFlush(PlaylistContent.create(playlist.getId(), CONTENT_ID_1));
        em.clear();

        playlistContentRepository.deleteByPlaylistIdAndContentId(playlist.getId(), CONTENT_ID_1);

        assertThat(playlistContentRepository.existsByPlaylistIdAndContentId(playlist.getId(), CONTENT_ID_1)).isFalse();
    }
}