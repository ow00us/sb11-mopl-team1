package com.mopl.playlist.service;

import com.mopl.content.repository.ContentRepository;
import com.mopl.global.config.JpaConfig;
import com.mopl.global.outbox.OutboxRecorderImpl;
import com.mopl.playlist.entity.Playlist;
import com.mopl.playlist.event.PlaylistSubscriptionEventFactory;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 실제 PostgreSQL 에서 플레이리스트 구독 멱등 계약(ADR 2)이 지켜지는지 검증한다.
 * <p>CodeRabbit 리뷰: 유니크 제약 위반 시 트랜잭션이 abort 되므로 catch 블록 안에서
 * 재조회하지 말고 {@code ON CONFLICT DO NOTHING} 로 처리하고, 신규 삽입 rows=1 일 때만
 * subscriberCount 를 증가시켜야 함.
 */
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
class PlaylistSubscribeIdempotencyIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    @Autowired PlaylistService playlistService;
    @Autowired PlaylistRepository playlistRepository;
    @Autowired PlaylistSubscriptionRepository subscriptionRepository;
    @Autowired JdbcTemplate jdbcTemplate;

    @SuppressWarnings("unused") @Autowired PlaylistContentRepository playlistContentRepository;
    @SuppressWarnings("unused") @Autowired ContentRepository contentRepository;

    private static final UUID OWNER_ID      = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final UUID SUBSCRIBER_ID = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");

    private Playlist playlist;

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("DELETE FROM playlist_subscriptions");
        jdbcTemplate.update("DELETE FROM playlists");
        jdbcTemplate.update("DELETE FROM users");

        Instant now = Instant.now();
        for (UUID uid : new UUID[]{OWNER_ID, SUBSCRIBER_ID}) {
            jdbcTemplate.update(
                    "INSERT INTO users (id, created_at, updated_at, email, password_hash, name, role) VALUES (?, ?, ?, ?, ?, ?, ?)",
                    uid, Timestamp.from(now), Timestamp.from(now),
                    uid + "@test.com", "hash", "user-" + uid, "USER"
            );
        }
        playlist = playlistRepository.saveAndFlush(
                Playlist.builder().ownerId(OWNER_ID).title("테스트").description("설명").build()
        );
    }

    @Test
    @DisplayName("첫 구독은 subscriberCount 를 1 로 증가시키고, 재구독은 카운트·행 재증가 없이 정상 완료된다")
    void subscribe_idempotent_countIncrementsOnce() {
        playlistService.subscribe(playlist.getId(), SUBSCRIBER_ID);
        playlistService.subscribe(playlist.getId(), SUBSCRIBER_ID);

        Playlist reloaded = playlistRepository.findById(playlist.getId()).orElseThrow();
        assertThat(reloaded.getSubscriberCount()).isEqualTo(1L);
        assertThat(subscriptionRepository
                .existsByPlaylistIdAndSubscriberId(playlist.getId(), SUBSCRIBER_ID)).isTrue();
        // 카운터는 맞아도 실제 행이 중복 저장될 가능성을 별개로 확인한다.
        assertThat(subscriptionRepository.findAll()).hasSize(1);
    }

    @Test
    @DisplayName("같은 사용자가 5번 연속 구독 요청해도 트랜잭션 오염 없이 카운트·행 모두 1 로 유지된다")
    void subscribe_repeatedCalls_neverPoisonTransaction() {
        for (int i = 0; i < 5; i++) {
            playlistService.subscribe(playlist.getId(), SUBSCRIBER_ID);
        }
        Playlist reloaded = playlistRepository.findById(playlist.getId()).orElseThrow();
        assertThat(reloaded.getSubscriberCount()).isEqualTo(1L);
        assertThat(subscriptionRepository.findAll()).hasSize(1);
    }
}