package com.mopl.watchingsession.presence;

import static org.assertj.core.api.Assertions.assertThat;

import com.mopl.global.config.RedisConfig;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@SpringBootTest(classes = {
    RedisConfig.class,
    JacksonAutoConfiguration.class,
    RedisAutoConfiguration.class,
    WatchingSessionPresenceReader.class,
    WatchingSessionPresenceWriter.class
})
@ActiveProfiles("test")
@Testcontainers
public class WatchingSessionPresenceReaderIntegrationTest {

    private static final UUID WATCHER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID CONTENT_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID OTHER_CONTENT_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final String SESSION_ID = "session-1";
    private static final String SUBSCRIPTION_ID = "sub-1";
    private static final String KEY = "mopl:presence:watcher:" + WATCHER_ID;
    private static final String CONTENT_ZSET_KEY = "mopl:presence:content:" + CONTENT_ID;
    private static final String OTHER_CONTENT_ZSET_KEY = "mopl:presence:content:" + OTHER_CONTENT_ID;

    @Autowired
    private WatchingSessionPresenceReader reader;

    @Autowired
    private WatchingSessionPresenceWriter writer;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @BeforeEach
    void clearPresenceKeys() {
        stringRedisTemplate.delete(KEY);
        stringRedisTemplate.delete(CONTENT_ZSET_KEY);
        stringRedisTemplate.delete(OTHER_CONTENT_ZSET_KEY);
    }

    @Container
    @ServiceConnection(name = "redis")
    static GenericContainer<?> redis =
        new GenericContainer<>(DockerImageName.parse("redis:7"))
            .withExposedPorts(6379);

    @Test
    @DisplayName("presence의 contentId가 일치하면 true를 반환한다")
    void isWatching_returnsTrue_whenContentMatches() {
        Instant now = Instant.now();
        writer.swap(WATCHER_ID, UUID.randomUUID(), CONTENT_ID, SESSION_ID, SUBSCRIPTION_ID, now, now, Duration.ofMinutes(30));

        boolean result = reader.isWatching(WATCHER_ID, CONTENT_ID);

        assertThat(result).isTrue();
    }

    @Test
    @DisplayName("다른 콘텐츠를 시청 중이면 false를 반환한다")
    void isWatching_returnsFalse_whenWatchingOtherContent() {
        Instant now = Instant.now();
        writer.swap(WATCHER_ID, UUID.randomUUID(), OTHER_CONTENT_ID, SESSION_ID, SUBSCRIPTION_ID, now, now, Duration.ofMinutes(30));

        boolean result = reader.isWatching(WATCHER_ID, CONTENT_ID);

        assertThat(result).isFalse();
    }

    @Test
    @DisplayName("presence 키가 없으면 false를 반환한다")
    void isWatching_returnsFalse_whenKeyDoesNotExist() {
        boolean result = reader.isWatching(WATCHER_ID, CONTENT_ID);

        assertThat(result).isFalse();
    }

    @Test
    @DisplayName("presence TTL이 만료되면 false를 반환한다")
    void isWatching_returnsFalse_afterTtlExpires() throws InterruptedException {
        Instant now = Instant.now();
        writer.swap(WATCHER_ID, UUID.randomUUID(), CONTENT_ID, SESSION_ID, SUBSCRIPTION_ID, now, now, Duration.ofMillis(200));

        Thread.sleep(300);

        boolean result = reader.isWatching(WATCHER_ID, CONTENT_ID);

        assertThat(result).isFalse();
    }

    @Test
    @DisplayName("레거시 문자열 타입 presence 키에 대해 WRONGTYPE 예외 없이 false를 반환한다")
    void isWatching_returnsFalse_forLegacyStringKey_withoutWrongTypeError() {
        stringRedisTemplate.opsForValue().set(KEY, "{\"legacy\":\"json\"}");

        boolean result = reader.isWatching(WATCHER_ID, CONTENT_ID);

        assertThat(result).isFalse();
    }

    @Test
    @DisplayName("presence TTL이 살아있는 시청자만 센다")
    void countByContent_countsOnlyActivePresence() {
        Instant now = Instant.now();
        writer.swap(WATCHER_ID, UUID.randomUUID(), CONTENT_ID, SESSION_ID, SUBSCRIPTION_ID, now, now, Duration.ofMinutes(30));
        writer.swap(UUID.randomUUID(), UUID.randomUUID(), CONTENT_ID, "session-2", "sub-2", now, now, Duration.ofMinutes(30));
        writer.swap(UUID.randomUUID(), UUID.randomUUID(), OTHER_CONTENT_ID, "session-3", "sub-3", now, now, Duration.ofMinutes(30));

        assertThat(reader.countByContent(CONTENT_ID)).isEqualTo(2L);
    }

    @Test
    @DisplayName("TTL 만료 후 감소 신호 없이도 카운트에서 자동으로 빠진다")
    void countByContent_excludesExpiredPresence_withoutDecrementSignal() throws InterruptedException {
        Instant now = Instant.now();
        writer.swap(WATCHER_ID, UUID.randomUUID(), CONTENT_ID, SESSION_ID, SUBSCRIPTION_ID, now, now, Duration.ofMillis(200));

        Thread.sleep(300);

        assertThat(reader.countByContent(CONTENT_ID)).isZero();
    }

    @Test
    @DisplayName("아무도 시청하지 않으면 0을 반환한다")
    void countByContent_returnsZero_whenNoWatchers() {
        assertThat(reader.countByContent(CONTENT_ID)).isZero();
    }
}
