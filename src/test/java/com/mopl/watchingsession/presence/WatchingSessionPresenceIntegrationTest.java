package com.mopl.watchingsession.presence;

import static com.mopl.global.util.InstantPrecisionUtils.normalizeToMicros;
import static org.assertj.core.api.Assertions.assertThat;

import com.mopl.global.config.RedisConfig;
import com.mopl.watchingsession.presence.WatchingSessionPresenceWriter.DeletedSnapshot;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
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
        Instant startedAt = Instant.now();
        Instant snapshotUpdatedAt = normalizeToMicros(startedAt);
        writer.swap(WATCHER_ID, SNAPSHOT_ID, CONTENT_ID, SESSION_ID, SUBSCRIPTION_ID, startedAt, snapshotUpdatedAt, DEFAULT_TTL);

        Map<Object, Object> entries = stringRedisTemplate.opsForHash().entries(KEY);
        assertThat(entries)
            .containsEntry("snapshotId", SNAPSHOT_ID.toString())
            .containsEntry("contentId", CONTENT_ID.toString())
            .containsEntry("sessionId", SESSION_ID)
            .containsEntry("subscriptionId", SUBSCRIPTION_ID)
            .containsEntry("startedAt", startedAt.toString())
            .containsEntry("snapshotUpdatedAt", snapshotUpdatedAt.toString());
        assertThat(entries).doesNotContainKey("watcherId"); // 키에 이미 있어 필드로 중복 저장하지 않음
    }

    @Test
    @DisplayName("swap()은 지정한 TTL을 실제 Redis 키에 적용한다")
    void swap_appliesTtl() {
        Instant now = Instant.now();
        writer.swap(WATCHER_ID, SNAPSHOT_ID, CONTENT_ID, SESSION_ID, SUBSCRIPTION_ID, now, normalizeToMicros(now),
            Duration.ofSeconds(60));

        Long ttl = stringRedisTemplate.getExpire(KEY, TimeUnit.SECONDS);
        assertThat(ttl).isGreaterThan(0).isLessThanOrEqualTo(60);
    }

    @Test
    @DisplayName("첫 swap()은 직전 소유자가 없어 빈 Optional을 반환한다")
    void swap_returnsEmpty_onFirstCall() {
        Instant now = Instant.now();
        Optional<WatchingPresence> previous = writer.swap(
            WATCHER_ID, SNAPSHOT_ID, CONTENT_ID, SESSION_ID, SUBSCRIPTION_ID, now, normalizeToMicros(now), DEFAULT_TTL);

        assertThat(previous).isEmpty();
    }

    @Test
    @DisplayName("연속된 swap()은 각각 자신이 밀어낸 직전 소유자를 정확히 하나씩 반환한다 (A->B->C 재구독)")
    void swap_returnsExactlyOnePreviousOwner_perCall_onChainedResubscribe() {
        Instant now = Instant.now();
        Instant snapshotUpdatedAt = normalizeToMicros(now);
        writer.swap(WATCHER_ID, UUID.randomUUID(), CONTENT_ID, "session-A", "sub-A", now, snapshotUpdatedAt, DEFAULT_TTL);

        Optional<WatchingPresence> previousForB = writer.swap(
            WATCHER_ID, UUID.randomUUID(), CONTENT_ID, "session-B", "sub-B", now, snapshotUpdatedAt, DEFAULT_TTL);
        Optional<WatchingPresence> previousForC = writer.swap(
            WATCHER_ID, UUID.randomUUID(), CONTENT_ID, "session-C", "sub-C", now, snapshotUpdatedAt, DEFAULT_TTL);

        assertThat(previousForB).isPresent();
        assertThat(previousForB.get().sessionId()).isEqualTo("session-A");
        assertThat(previousForC).isPresent();
        assertThat(previousForC.get().sessionId()).isEqualTo("session-B");
    }

    @Test
    @DisplayName("deleteIfOwner()는 소유권이 일치할 때만 실제로 키를 삭제한다")
    void deleteIfOwner_removesKey_onlyWhenOwnerMatches() {
        Instant now = Instant.now();
        Instant snapshotUpdatedAt = normalizeToMicros(now);
        writer.swap(WATCHER_ID, SNAPSHOT_ID, CONTENT_ID, SESSION_ID, SUBSCRIPTION_ID, now, snapshotUpdatedAt, DEFAULT_TTL);

        Optional<DeletedSnapshot> deletedByOtherOwner = writer.deleteIfOwner(WATCHER_ID, "other-session", "other-sub");
        assertThat(deletedByOtherOwner).isEmpty();
        assertThat(stringRedisTemplate.hasKey(KEY)).isTrue(); // 낡은 요청이 현재 세션을 지우지 않음

        Optional<DeletedSnapshot> deletedByRealOwner = writer.deleteIfOwner(WATCHER_ID, SESSION_ID, SUBSCRIPTION_ID);
        assertThat(deletedByRealOwner).isPresent();
        assertThat(deletedByRealOwner.get().snapshotId()).isEqualTo(SNAPSHOT_ID);
        assertThat(deletedByRealOwner.get().snapshotUpdatedAt()).isEqualTo(snapshotUpdatedAt);
        assertThat(stringRedisTemplate.hasKey(KEY)).isFalse();
    }

    @Test
    @DisplayName("renewIfOwner()는 소유권이 일치할 때만 TTL을 연장하고, 저장된 값은 바꾸지 않는다")
    void renewIfOwner_extendsTtl_onlyWhenOwnerMatches_andKeepsValue() {
        Instant now = Instant.now();
        writer.swap(WATCHER_ID, SNAPSHOT_ID, CONTENT_ID, SESSION_ID, SUBSCRIPTION_ID, now, normalizeToMicros(now),
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
    @DisplayName("만료된 presence에 대한 deleteIfOwner()는 빈 Optional을 반환한다 (TTL이 소유권 수명)")
    void deleteIfOwner_returnsEmpty_afterTtlExpires() throws InterruptedException {
        Instant now = Instant.now();
        writer.swap(WATCHER_ID, SNAPSHOT_ID, CONTENT_ID, SESSION_ID, SUBSCRIPTION_ID, now, normalizeToMicros(now),
            Duration.ofMillis(200));

        Thread.sleep(300);

        Optional<DeletedSnapshot> result = writer.deleteIfOwner(WATCHER_ID, SESSION_ID, SUBSCRIPTION_ID);
        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("deleteIfOwnerSession()은 sessionId가 일치할 때만 실제로 키를 삭제한다 (subscriptionId 무관)")
    void deleteIfOwnerSession_removesKey_onlyWhenSessionMatches() {
        Instant now = Instant.now();
        Instant snapshotUpdatedAt = normalizeToMicros(now);
        writer.swap(WATCHER_ID, SNAPSHOT_ID, CONTENT_ID, SESSION_ID, SUBSCRIPTION_ID, now, snapshotUpdatedAt, DEFAULT_TTL);

        Optional<DeletedSnapshot> deletedByOtherSession = writer.deleteIfOwnerSession(WATCHER_ID, "other-session");
        assertThat(deletedByOtherSession).isEmpty();
        assertThat(stringRedisTemplate.hasKey(KEY)).isTrue(); // 다른 연결이 현재 세션을 지우지 않음

        Optional<DeletedSnapshot> deletedBySameSession = writer.deleteIfOwnerSession(WATCHER_ID, SESSION_ID);
        assertThat(deletedBySameSession).isPresent();
        assertThat(deletedBySameSession.get().snapshotId()).isEqualTo(SNAPSHOT_ID);
        assertThat(deletedBySameSession.get().snapshotUpdatedAt()).isEqualTo(snapshotUpdatedAt);
        assertThat(stringRedisTemplate.hasKey(KEY)).isFalse();
    }

    @Test
    @DisplayName("만료된 presence에 대한 deleteIfOwnerSession()은 빈 Optional을 반환한다 (TTL이 소유권 수명)")
    void deleteIfOwnerSession_returnsEmpty_afterTtlExpires() throws InterruptedException {
        Instant now = Instant.now();
        writer.swap(WATCHER_ID, SNAPSHOT_ID, CONTENT_ID, SESSION_ID, SUBSCRIPTION_ID, now, normalizeToMicros(now),
            Duration.ofMillis(200));

        Thread.sleep(300);

        Optional<DeletedSnapshot> result = writer.deleteIfOwnerSession(WATCHER_ID, SESSION_ID);
        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("레거시 문자열 타입 presence 키에 대해 deleteIfOwnerSession()은 예외 없이 빈 Optional을 반환한다")
    void deleteIfOwnerSession_returnsEmpty_forLegacyStringKey_withoutWrongTypeError() {
        stringRedisTemplate.opsForValue().set(KEY, "{\"legacy\":\"json\"}");

        Optional<DeletedSnapshot> result = writer.deleteIfOwnerSession(WATCHER_ID, SESSION_ID);

        assertThat(result).isEmpty();
        assertThat(stringRedisTemplate.opsForValue().get(KEY)).isEqualTo("{\"legacy\":\"json\"}"); // 건드리지 않음
    }

    @Test
    @DisplayName("레거시 문자열 타입 presence 키가 있어도 WRONGTYPE 없이 swap()이 성공하고 갈아치운다")
    void swap_overwritesLegacyStringKey_withoutWrongTypeError() {
        stringRedisTemplate.opsForValue().set(KEY, "{\"legacy\":\"json\"}");
        Instant now = Instant.now();

        Optional<WatchingPresence> previous = writer.swap(
            WATCHER_ID, SNAPSHOT_ID, CONTENT_ID, SESSION_ID, SUBSCRIPTION_ID, now, normalizeToMicros(now), DEFAULT_TTL);

        assertThat(previous).isEmpty(); // 레거시 값은 파싱하지 않고 "직전 소유자 없음"으로 취급
        assertThat(stringRedisTemplate.opsForHash().get(KEY, "sessionId")).isEqualTo(SESSION_ID);
    }

    @Test
    @DisplayName("레거시 문자열 타입 presence 키에 대해 deleteIfOwner()는 예외 없이 빈 Optional을 반환한다")
    void deleteIfOwner_returnsEmpty_forLegacyStringKey_withoutWrongTypeError() {
        stringRedisTemplate.opsForValue().set(KEY, "{\"legacy\":\"json\"}");

        Optional<DeletedSnapshot> result = writer.deleteIfOwner(WATCHER_ID, SESSION_ID, SUBSCRIPTION_ID);

        assertThat(result).isEmpty();
        assertThat(stringRedisTemplate.opsForValue().get(KEY)).isEqualTo("{\"legacy\":\"json\"}"); // 건드리지 않음
    }

    @Test
    @DisplayName("snapshotUpdatedAt 필드 없이 저장된 구버전 레코드도 deleteIfOwner가 정상 삭제하고 토큰은 null로 반환한다")
    void deleteIfOwner_deletesLegacyRecord_withoutSnapshotUpdatedAtField() {
        // 구버전 형식을 그대로 재현 - snapshotUpdatedAt 필드 없이 직접 HSET
        stringRedisTemplate.opsForHash().putAll(KEY, Map.of(
            "snapshotId", SNAPSHOT_ID.toString(),
            "contentId", CONTENT_ID.toString(),
            "sessionId", SESSION_ID,
            "subscriptionId", SUBSCRIPTION_ID,
            "startedAt", Instant.now().toString()));

        Optional<WatchingSessionPresenceWriter.DeletedSnapshot> result =
            writer.deleteIfOwner(WATCHER_ID, SESSION_ID, SUBSCRIPTION_ID);

        assertThat(result).isPresent();
        assertThat(result.get().snapshotId()).isEqualTo(SNAPSHOT_ID);
        assertThat(result.get().snapshotUpdatedAt()).isNull();
        assertThat(stringRedisTemplate.hasKey(KEY)).isFalse();
    }

    @Test
    @DisplayName("레거시 문자열 타입 presence 키에 대해 renewIfOwner()는 예외 없이 false를 반환한다")
    void renewIfOwner_returnsFalse_forLegacyStringKey_withoutWrongTypeError() {
        stringRedisTemplate.opsForValue().set(KEY, "{\"legacy\":\"json\"}");

        boolean result = writer.renewIfOwner(WATCHER_ID, SESSION_ID, SUBSCRIPTION_ID, Duration.ofSeconds(60));

        assertThat(result).isFalse();
    }

    @Test
    @DisplayName("findExistingWatcherIds()는 실제 존재하는 키만 반환한다")
    void findExistingWatcherIds_returnsOnlyRealKeys() {
        UUID neverCreatedWatcher = UUID.randomUUID();
        Instant now = Instant.now();
        writer.swap(WATCHER_ID, SNAPSHOT_ID, CONTENT_ID, SESSION_ID, SUBSCRIPTION_ID, now, normalizeToMicros(now), DEFAULT_TTL);

        Set<UUID> result = writer.findExistingWatcherIds(List.of(WATCHER_ID, neverCreatedWatcher));

        assertThat(result).containsExactly(WATCHER_ID);
    }

    @Test
    @DisplayName("updateSnapshotIdIfOwner()는 소유권이 일치할 때만 snapshotId와 snapshotUpdatedAt 필드를 교체하고, 다른 필드는 유지한다")
    void updateSnapshotIdIfOwner_replacesOnlySnapshotIdField_whenOwnerMatches() {
        Instant now = Instant.now();
        Instant originalUpdatedAt = normalizeToMicros(now);
        writer.swap(WATCHER_ID, SNAPSHOT_ID, CONTENT_ID, SESSION_ID, SUBSCRIPTION_ID, now, originalUpdatedAt, DEFAULT_TTL);

        UUID newSnapshotId = UUID.randomUUID();
        Instant newSnapshotUpdatedAt = normalizeToMicros(Instant.now().plusSeconds(10));

        boolean updated = writer.updateSnapshotIdIfOwner(
            WATCHER_ID, SESSION_ID, SUBSCRIPTION_ID, newSnapshotId, newSnapshotUpdatedAt);

        assertThat(updated).isTrue();
        Map<Object, Object> entries = stringRedisTemplate.opsForHash().entries(KEY);
        assertThat(entries.get("snapshotId")).isEqualTo(newSnapshotId.toString());
        assertThat(entries.get("snapshotUpdatedAt")).isEqualTo(newSnapshotUpdatedAt.toString());
        assertThat(entries.get("sessionId")).isEqualTo(SESSION_ID); // 다른 필드는 그대로 유지
    }

    @Test
    @DisplayName("updateSnapshotIdIfOwner()는 소유권이 불일치하면 필드를 바꾸지 않고 false를 반환한다")
    void updateSnapshotIdIfOwner_doesNotChangeField_whenOwnerMismatches() {
        Instant now = Instant.now();
        Instant originalUpdatedAt = normalizeToMicros(now);
        writer.swap(WATCHER_ID, SNAPSHOT_ID, CONTENT_ID, SESSION_ID, SUBSCRIPTION_ID, now, originalUpdatedAt, DEFAULT_TTL);

        UUID newSnapshotId = UUID.randomUUID();
        Instant newSnapshotUpdatedAt = normalizeToMicros(Instant.now().plusSeconds(10));

        boolean updated = writer.updateSnapshotIdIfOwner(
            WATCHER_ID, "other-session", SUBSCRIPTION_ID, newSnapshotId, newSnapshotUpdatedAt);

        assertThat(updated).isFalse();
        Map<Object, Object> entries = stringRedisTemplate.opsForHash().entries(KEY);
        assertThat(entries.get("snapshotId")).isEqualTo(SNAPSHOT_ID.toString());
        assertThat(entries.get("snapshotUpdatedAt")).isEqualTo(originalUpdatedAt.toString());
    }
}
