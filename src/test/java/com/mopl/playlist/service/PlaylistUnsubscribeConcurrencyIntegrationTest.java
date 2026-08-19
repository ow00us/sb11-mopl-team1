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
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 실제 PostgreSQL 에서 동일 사용자의 동시 unsubscribe 요청이 발생해도
 * {@code playlists.subscriber_count} 가 실제 구독 수 감소만큼만 줄어드는지 검증한다.
 * <p>이슈 #131: 기존 구현은 {@code findBy → delete → decrement} 순서로 실행하는데
 * READ_COMMITTED 스냅샷에서 두 트랜잭션이 모두 find 를 통과할 수 있고, 늦은 트랜잭션의
 * delete 는 rows=0 no-op 이지만 decrement 는 조건 없이 실행되어 카운터가 실구독수보다
 * 낮게 떨어지는 race 가 존재했다.
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
class PlaylistUnsubscribeConcurrencyIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    @Autowired PlaylistService playlistService;
    @Autowired PlaylistRepository playlistRepository;
    @Autowired PlaylistSubscriptionRepository subscriptionRepository;
    @Autowired JdbcTemplate jdbcTemplate;

    @SuppressWarnings("unused") @Autowired PlaylistContentRepository playlistContentRepository;
    @SuppressWarnings("unused") @Autowired ContentRepository contentRepository;

    private static final UUID OWNER_ID       = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final UUID SUBSCRIBER_ID  = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
    private static final UUID OTHER_SUB_ID   = UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc");

    private Playlist playlist;

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("DELETE FROM playlist_subscriptions");
        jdbcTemplate.update("DELETE FROM playlists");
        jdbcTemplate.update("DELETE FROM users");

        Instant now = Instant.now();
        for (UUID uid : new UUID[]{OWNER_ID, SUBSCRIBER_ID, OTHER_SUB_ID}) {
            jdbcTemplate.update(
                    "INSERT INTO users (id, created_at, updated_at, email, password_hash, name, role) VALUES (?, ?, ?, ?, ?, ?, ?)",
                    uid, Timestamp.from(now), Timestamp.from(now),
                    uid + "@test.com", "hash", "user-" + uid, "USER"
            );
        }
        playlist = playlistRepository.saveAndFlush(
                Playlist.builder().ownerId(OWNER_ID).title("테스트").description("설명").build()
        );

        // 두 명이 구독한 상태에서 시작한다. 이후 SUBSCRIBER_ID 만 동시에 두 번 unsubscribe 를
        // 시도하므로 정상 동작이면 카운트는 2 → 1 로 한 번만 감소해야 한다.
        playlistService.subscribe(playlist.getId(), SUBSCRIBER_ID);
        playlistService.subscribe(playlist.getId(), OTHER_SUB_ID);
        assertThat(playlistRepository.findById(playlist.getId()).orElseThrow().getSubscriberCount())
                .isEqualTo(2L);
    }

    @Test
    @DisplayName("동일 사용자의 동시 unsubscribe 두 번은 subscriber_count 를 정확히 한 번만 감소시킨다")
    void unsubscribe_concurrent_decrementsCountExactlyOnce() throws Exception {
        // 두 스레드를 CountDownLatch 로 동시에 출발시켜 race window 를 만든다.
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(2);
        // 재취소가 멱등이 되어 정상 경로에서는 예외가 나지 않아야 한다.
        // 스레드 내부 예외를 삼키면 한쪽이 실패해도 카운터 검증이 통과할 수 있으므로 모두 수집해서 이후 검증한다.
        List<Throwable> workerErrors = new CopyOnWriteArrayList<>();
        Runnable task = () -> {
            try {
                start.await();
                playlistService.unsubscribe(playlist.getId(), SUBSCRIBER_ID);
            } catch (Throwable t) {
                workerErrors.add(t);
            } finally {
                done.countDown();
            }
        };
        new Thread(task).start();
        new Thread(task).start();
        start.countDown();
        assertThat(done.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(workerErrors)
                .as("동시 unsubscribe 워커에서 예외가 발생하면 안 된다")
                .isEmpty();

        // SUBSCRIBER_ID 는 몇 번 요청했든 실제로는 한 번만 구독을 취소했으므로
        // 카운터는 실구독수와 일치하는 1 이어야 한다.
        Playlist reloaded = playlistRepository.findById(playlist.getId()).orElseThrow();
        assertThat(reloaded.getSubscriberCount()).isEqualTo(1L);

        // 남아있는 실제 구독은 OTHER_SUB_ID 하나뿐이다.
        assertThat(subscriptionRepository.findAll()).hasSize(1);
        assertThat(subscriptionRepository
                .existsByPlaylistIdAndSubscriberId(playlist.getId(), OTHER_SUB_ID)).isTrue();
        assertThat(subscriptionRepository
                .existsByPlaylistIdAndSubscriberId(playlist.getId(), SUBSCRIBER_ID)).isFalse();
    }
}