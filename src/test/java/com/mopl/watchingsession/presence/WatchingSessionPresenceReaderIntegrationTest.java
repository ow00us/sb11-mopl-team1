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

    @Autowired
    private WatchingSessionPresenceReader reader;

    @Autowired
    private WatchingSessionPresenceWriter writer;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @BeforeEach
    void clearPresenceKeys() {
        stringRedisTemplate.delete(KEY);
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

}
