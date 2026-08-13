package com.mopl.watchingsession.presence;

import static org.assertj.core.api.Assertions.assertThat;

import com.mopl.global.config.RedisConfig;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@SpringBootTest(classes = {
    RedisConfig.class,
    JacksonAutoConfiguration.class,
    RedisAutoConfiguration.class,
    WatchingSessionPresenceWriter.class
})
@ActiveProfiles("test")
@Testcontainers
public class WatchingSessionPresenceIntegrationTest {

    @Container
    @ServiceConnection(name = "redis")
    static GenericContainer<?> redis =
        new GenericContainer<>(DockerImageName.parse("redis:7"))
            .withExposedPorts(6379);

    private static final UUID WATCHER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID CONTENT_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final String SESSION_ID = "session-1";
    private static final String SUBSCRIPTION_ID = "sub-1";
    private static final String KEY = "mopl:presence:watcher:" + WATCHER_ID;
    private static final Duration DEFAULT_TTL = Duration.ofMinutes(30);

    @Autowired
    private WatchingSessionPresenceWriter writer;

    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    @BeforeEach
    void clearPresenceKeys() {
        redisTemplate.delete(KEY);
    }

    @Test
    @DisplayName("write()는 실제 Redis에 WatchingPresence 값을 그대로 저장한다")
    void write_actuallyStoresPresenceInRedis() {
        Instant startedAt = Instant.now();

        writer.write(WATCHER_ID, CONTENT_ID, SESSION_ID, SUBSCRIPTION_ID, startedAt, DEFAULT_TTL);

        Object stored = redisTemplate.opsForValue().get(KEY);
        assertThat(stored).isInstanceOf(WatchingPresence.class);
        WatchingPresence presence = (WatchingPresence) stored;
        assertThat(presence.watcherId()).isEqualTo(WATCHER_ID);
        assertThat(presence.contentId()).isEqualTo(CONTENT_ID);
        assertThat(presence.sessionId()).isEqualTo(SESSION_ID);
        assertThat(presence.subscriptionId()).isEqualTo(SUBSCRIPTION_ID);
        assertThat(presence.startedAt()).isEqualTo(startedAt);
    }

    @Test
    @DisplayName("write()는 지정한 TTL을 실제 Redis 키에 적용한다")
    void write_actuallyAppliesTtlInRedis() {
        writer.write(WATCHER_ID, CONTENT_ID, SESSION_ID, SUBSCRIPTION_ID, Instant.now(), Duration.ofSeconds(60));

        Long ttl = redisTemplate.getExpire(KEY, TimeUnit.SECONDS);

        assertThat(ttl).isGreaterThan(0).isLessThanOrEqualTo(60);
    }

    @Test
    @DisplayName("같은 watcherId로 다시 write()하면 기존 키를 덮어쓰고 최신 값만 남는다")
    void write_overwritesExistingKeyForSameWatcher() {
        writer.write(WATCHER_ID, CONTENT_ID, SESSION_ID, SUBSCRIPTION_ID, Instant.now(), DEFAULT_TTL);

        UUID newContentId = UUID.randomUUID();
        writer.write(WATCHER_ID, newContentId, SESSION_ID, "sub-2", Instant.now(), DEFAULT_TTL);

        Object stored = redisTemplate.opsForValue().get(KEY);
        assertThat(stored).isInstanceOf(WatchingPresence.class);
        WatchingPresence presence = (WatchingPresence) stored;
        assertThat(presence.contentId()).isEqualTo(newContentId);
        assertThat(presence.subscriptionId()).isEqualTo("sub-2");
    }

    @Test
    @DisplayName("delete()는 실제 Redis에서 키를 제거한다")
    void delete_actuallyRemovesKeyFromRedis() {
        writer.write(WATCHER_ID, CONTENT_ID, SESSION_ID, SUBSCRIPTION_ID, Instant.now(), DEFAULT_TTL);
        assertThat(redisTemplate.hasKey(KEY)).isTrue();

        writer.delete(WATCHER_ID);

        assertThat(redisTemplate.hasKey(KEY)).isFalse();
        assertThat(redisTemplate.opsForValue().get(KEY)).isNull();
    }

    @Test
    @DisplayName("존재하지 않는 키에 delete()를 호출해도 아무 일도 일어나지 않는다")
    void delete_doesNothing_whenKeyDoesNotExist() {
        UUID neverWrittenWatcherId = UUID.randomUUID();

        writer.delete(neverWrittenWatcherId);

        assertThat(redisTemplate.hasKey("mopl:presence:watcher:" + neverWrittenWatcherId)).isFalse();
    }

    @Test
    @DisplayName("renew()는 실제 Redis에서 기존 키의 TTL을 재설정한다")
    void renew_actuallyResetsTtlInRedis() {
        writer.write(WATCHER_ID, CONTENT_ID, SESSION_ID, SUBSCRIPTION_ID, Instant.now(), Duration.ofSeconds(5));

        boolean result = writer.renew(WATCHER_ID, Duration.ofSeconds(60));

        assertThat(result).isTrue();
        Long ttl = redisTemplate.getExpire(KEY, TimeUnit.SECONDS);
        assertThat(ttl).isGreaterThan(5).isLessThanOrEqualTo(60);
    }

    @Test
    @DisplayName("renew()는 존재하지 않는 키에 대해 false를 반환하고 키를 새로 생성하지 않는다")
    void renew_doesNotCreateKey_whenKeyDoesNotExists() {
        boolean result = writer.renew(WATCHER_ID, Duration.ofSeconds(60));

        assertThat(result).isFalse();
        assertThat(redisTemplate.hasKey(KEY)).isFalse();
    }

    @Test
    @DisplayName("renew()는 저장된 presence 값 자체는 변경하지 않는다")
    void renew_doesNotModifyStoredValue() {
        Instant startedAt = Instant.now();
        writer.write(WATCHER_ID, CONTENT_ID, SESSION_ID, SUBSCRIPTION_ID, startedAt, DEFAULT_TTL);

        writer.renew(WATCHER_ID, Duration.ofSeconds(60));

        WatchingPresence presence = (WatchingPresence) redisTemplate.opsForValue().get(KEY);
        assertThat(presence.contentId()).isEqualTo(CONTENT_ID);
        assertThat(presence.startedAt()).isEqualTo(startedAt);
    }
}
