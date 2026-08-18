package com.mopl.watchingsession.presence;

import static org.assertj.core.api.Assertions.assertThat;

import com.mopl.global.config.RedisConfig;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
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
    private static final UUID SNAPSHOT_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final String SESSION_ID = "session-1";
    private static final String SUBSCRIPTION_ID = "sub-1";
    private static final String KEY = "mopl:presence:watcher:" + WATCHER_ID;
    private static final Duration DEFAULT_TTL = Duration.ofMinutes(30);

    @Autowired
    private WatchingSessionPresenceWriter writer;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @BeforeEach
    void clearPresenceKeys() {
        stringRedisTemplate.delete(KEY);
    }

    @Test
    @DisplayName("swap()은 값을 Hash로 저장하고, 필드 이름·값이 전부 평문 문자열이다")
    void swap_storesValueAsPlainStringHash() {
        writer.swap(WATCHER_ID, SNAPSHOT_ID, CONTENT_ID, SESSION_ID, SUBSCRIPTION_ID, Instant.now(), DEFAULT_TTL);

        Map<Object, Object> entries = stringRedisTemplate.opsForHash().entries(KEY);
        assertThat(entries)
            .containsEntry("snapshotId", SNAPSHOT_ID.toString())
            .containsEntry("contentId", CONTENT_ID.toString())
            .containsEntry("sessionId", SESSION_ID)
            .containsEntry("subscriptionId", SUBSCRIPTION_ID);
        assertThat(entries).doesNotContainKey("watcherId"); // 키에 이미 있어 필드로 중복 저장하지 않음
    }

    @Test
    @DisplayName("swap()은 지정한 TTL을 실제 Redis 키에 적용한다")
    void swap_appliesTtl() {
        writer.swap(WATCHER_ID, SNAPSHOT_ID, CONTENT_ID, SESSION_ID, SUBSCRIPTION_ID, Instant.now(),
            Duration.ofSeconds(60));

        Long ttl = stringRedisTemplate.getExpire(KEY, TimeUnit.SECONDS);
        assertThat(ttl).isGreaterThan(0).isLessThanOrEqualTo(60);
    }

    @Test
    @DisplayName("첫 swap()은 직전 소유자가 없어 빈 Optional을 반환한다")
    void swap_returnsEmpty_onFirstCall() {
        Optional<WatchingPresence> previous = writer.swap(
            WATCHER_ID, SNAPSHOT_ID, CONTENT_ID, SESSION_ID, SUBSCRIPTION_ID, Instant.now(), DEFAULT_TTL);

        assertThat(previous).isEmpty();
    }

    @Test
    @DisplayName("연속된 swap()은 각각 자신이 밀어낸 직전 소유자를 정확히 하나씩 반환한다 (A->B->C 재구독)")
    void swap_returnsExactlyOnePreviousOwner_perCall_onChainedResubscribe() {
        writer.swap(WATCHER_ID, UUID.randomUUID(), CONTENT_ID, "session-A", "sub-A", Instant.now(), DEFAULT_TTL);

        Optional<WatchingPresence> previousForB = writer.swap(
            WATCHER_ID, UUID.randomUUID(), CONTENT_ID, "session-B", "sub-B", Instant.now(), DEFAULT_TTL);
        Optional<WatchingPresence> previousForC = writer.swap(
            WATCHER_ID, UUID.randomUUID(), CONTENT_ID, "session-C", "sub-C", Instant.now(), DEFAULT_TTL);

        assertThat(previousForB).isPresent();
        assertThat(previousForB.get().sessionId()).isEqualTo("session-A");
        assertThat(previousForC).isPresent();
        assertThat(previousForC.get().sessionId()).isEqualTo("session-B");
    }

    @Test
    @DisplayName("deleteIfOwner()는 소유권이 일치할 때만 실제로 키를 삭제한다")
    void deleteIfOwner_removesKey_onlyWhenOwnerMatches() {
        writer.swap(WATCHER_ID, SNAPSHOT_ID, CONTENT_ID, SESSION_ID, SUBSCRIPTION_ID, Instant.now(), DEFAULT_TTL);

        boolean deletedByOtherOwner = writer.deleteIfOwner(WATCHER_ID, "other-session", "other-sub");
        assertThat(deletedByOtherOwner).isFalse();
        assertThat(stringRedisTemplate.hasKey(KEY)).isTrue(); // 낡은 요청이 현재 세션을 지우지 않음

        boolean deletedByRealOwner = writer.deleteIfOwner(WATCHER_ID, SESSION_ID, SUBSCRIPTION_ID);
        assertThat(deletedByRealOwner).isTrue();
        assertThat(stringRedisTemplate.hasKey(KEY)).isFalse();
    }

    @Test
    @DisplayName("renewIfOwner()는 소유권이 일치할 때만 TTL을 연장하고, 저장된 값은 바꾸지 않는다")
    void renewIfOwner_extendsTtl_onlyWhenOwnerMatches_andKeepsValue() {
        writer.swap(WATCHER_ID, SNAPSHOT_ID, CONTENT_ID, SESSION_ID, SUBSCRIPTION_ID, Instant.now(),
            Duration.ofSeconds(5));

        boolean renewedByOtherOwner = writer.renewIfOwner(WATCHER_ID, "other-session", "other-sub",
            Duration.ofSeconds(60));
        assertThat(renewedByOtherOwner).isFalse();

        boolean renewedByRealOwner = writer.renewIfOwner(WATCHER_ID, SESSION_ID, SUBSCRIPTION_ID,
            Duration.ofSeconds(60));
        assertThat(renewedByRealOwner).isTrue();

        Long ttl = stringRedisTemplate.getExpire(KEY, TimeUnit.SECONDS);
        assertThat(ttl).isGreaterThan(5).isLessThanOrEqualTo(60);
        assertThat(stringRedisTemplate.opsForHash().get(KEY, "sessionId")).isEqualTo(SESSION_ID);
    }

    @Test
    @DisplayName("renewIfOwner()는 존재하지 않는 키에 대해 false를 반환하고 키를 새로 만들지 않는다")
    void renewIfOwner_doesNotCreateKey_whenKeyDoesNotExist() {
        boolean result = writer.renewIfOwner(WATCHER_ID, SESSION_ID, SUBSCRIPTION_ID, Duration.ofSeconds(60));

        assertThat(result).isFalse();
        assertThat(stringRedisTemplate.hasKey(KEY)).isFalse();
    }

    @Test
    @DisplayName("만료된 presence에 대한 deleteIfOwner()는 false를 반환한다 (TTL이 소유권 수명)")
    void deleteIfOwner_returnsFalse_afterTtlExpires() throws InterruptedException {
        writer.swap(WATCHER_ID, SNAPSHOT_ID, CONTENT_ID, SESSION_ID, SUBSCRIPTION_ID, Instant.now(),
            Duration.ofMillis(200));

        Thread.sleep(300);

        boolean result = writer.deleteIfOwner(WATCHER_ID, SESSION_ID, SUBSCRIPTION_ID);
        assertThat(result).isFalse();
    }
}
