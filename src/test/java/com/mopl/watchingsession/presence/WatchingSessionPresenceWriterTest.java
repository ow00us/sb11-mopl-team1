package com.mopl.watchingsession.presence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

public class WatchingSessionPresenceWriterTest {

    private static final UUID WATCHER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID CONTENT_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID SNAPSHOT_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final String SESSION_ID = "session-1";
    private static final String SUBSCRIPTION_ID = "sub-1";
    private static final String EXPECTED_KEY = "mopl:presence:watcher:" + WATCHER_ID;

    private final StringRedisTemplate stringRedisTemplate = mock(StringRedisTemplate.class);
    private final WatchingSessionPresenceWriter writer = new WatchingSessionPresenceWriter(stringRedisTemplate);

    private static <T> RedisScript<T> anyScript() {
        return any(RedisScript.class);
    }

    @Test
    @DisplayName("swap()은 직전 필드를 WatchingPresence로 복원해 반환한다")
    void swap_parsesPreviousPresenceFromScriptResult() {
        Instant previousStartedAt = Instant.parse("2026-08-18T00:00:00Z");
        Instant newStartedAt = Instant.now();
        List<String> previousFields = List.of(
            "snapshotId", SNAPSHOT_ID.toString(),
            "contentId", CONTENT_ID.toString(),
            "sessionId", "old-session",
            "subscriptionId", "old-sub",
            "startedAt", previousStartedAt.toString());

        when(stringRedisTemplate.execute(anyScript(), eq(List.of(EXPECTED_KEY)),
            eq(SNAPSHOT_ID.toString()), eq(CONTENT_ID.toString()), eq(SESSION_ID), eq(SUBSCRIPTION_ID),
            eq(newStartedAt.toString()), eq("60000")))
            .thenReturn(previousFields);

        Optional<WatchingPresence> result = writer.swap(
            WATCHER_ID, SNAPSHOT_ID, CONTENT_ID, SESSION_ID, SUBSCRIPTION_ID, newStartedAt, Duration.ofSeconds(60));

        assertThat(result).isPresent();
        assertThat(result.get().watcherId()).isEqualTo(WATCHER_ID);
        assertThat(result.get().snapshotId()).isEqualTo(SNAPSHOT_ID);
        assertThat(result.get().contentId()).isEqualTo(CONTENT_ID);
        assertThat(result.get().sessionId()).isEqualTo("old-session");
        assertThat(result.get().subscriptionId()).isEqualTo("old-sub");
        assertThat(result.get().startedAt()).isEqualTo(previousStartedAt);
    }

    @Test
    @DisplayName("swap()은 직전 소유자가 없으면(빈 배열) 빈 Optional을 반환한다")
    void swap_returnsEmpty_whenNoPreviousOwner() {
        when(stringRedisTemplate.execute(anyScript(), eq(List.of(EXPECTED_KEY)),
            any(), any(), any(), any(), any(), any()))
            .thenReturn(List.of());

        Optional<WatchingPresence> result = writer.swap(
            WATCHER_ID, SNAPSHOT_ID, CONTENT_ID, SESSION_ID, SUBSCRIPTION_ID, Instant.now(), Duration.ofSeconds(60));

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("swap()은 저장된 필드가 불완전하면(스키마 전환 중 옛 형식) 빈 Optional을 반환한다")
    void swap_returnsEmpty_whenFieldsIncomplete() {
        when(stringRedisTemplate.execute(anyScript(), eq(List.of(EXPECTED_KEY)),
            any(), any(), any(), any(), any(), any()))
            .thenReturn(List.of("sessionId", "old-session"));

        Optional<WatchingPresence> result = writer.swap(
            WATCHER_ID, SNAPSHOT_ID, CONTENT_ID, SESSION_ID, SUBSCRIPTION_ID, Instant.now(), Duration.ofSeconds(60));

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("swap() 도중 Redis 예외가 나면 호출자에게 그대로 전파된다 (소유권 원본이라 격리하지 않음)")
    void swap_propagatesRedisFailure() {
        when(stringRedisTemplate.execute(anyScript(), eq(List.of(EXPECTED_KEY)),
            any(), any(), any(), any(), any(), any()))
            .thenThrow(new RuntimeException("Redis 연결 끊김"));

        assertThatThrownBy(() -> writer.swap(
            WATCHER_ID, SNAPSHOT_ID, CONTENT_ID, SESSION_ID, SUBSCRIPTION_ID, Instant.now(), Duration.ofSeconds(60)))
            .isInstanceOf(RuntimeException.class);
    }

    @Test
    @DisplayName("deleteIfOwner()는 소유권이 일치하면 삭제된 presence의 snapshotId를 반환한다")
    void deleteIfOwner_returnsTrue_whenScriptReturnsOne() {
        when(stringRedisTemplate.execute(anyScript(), eq(List.of(EXPECTED_KEY)),
            eq(SESSION_ID), eq(SUBSCRIPTION_ID)))
            .thenReturn(List.of("1", SNAPSHOT_ID.toString()));

        Optional<UUID> result = writer.deleteIfOwner(WATCHER_ID, SESSION_ID, SUBSCRIPTION_ID);

        assertThat(result).contains(SNAPSHOT_ID);
    }

    @Test
    @DisplayName("deleteIfOwner()는 소유권 불일치(0)일 때 빈 Optional을 반환한다")
    void deleteIfOwner_returnsEmpty_whenOwnerMismatch() {
        when(stringRedisTemplate.execute(anyScript(), eq(List.of(EXPECTED_KEY)),
            eq(SESSION_ID), eq(SUBSCRIPTION_ID)))
            .thenReturn(List.of("0", ""));

        assertThat(writer.deleteIfOwner(WATCHER_ID, SESSION_ID, SUBSCRIPTION_ID)).isEmpty();
    }

    @Test
    @DisplayName("deleteIfOwner()는 활성 세션 없음(-1)일 때도 빈 Optional을 반환한다")
    void deleteIfOwner_returnsEmpty_whenNoActiveSession() {
        when(stringRedisTemplate.execute(anyScript(), eq(List.of(EXPECTED_KEY)),
            eq(SESSION_ID), eq(SUBSCRIPTION_ID)))
            .thenReturn(List.of("-1", ""));

        assertThat(writer.deleteIfOwner(WATCHER_ID, SESSION_ID, SUBSCRIPTION_ID)).isEmpty();
    }

    @Test
    @DisplayName("deleteIfOwner()는 snapshotId 파싱에 실패하면 방어적으로 빈 Optional을 반환한다")
    void deleteIfOwner_returnsEmpty_whenSnapshotIdMissing() {
        when(stringRedisTemplate.execute(anyScript(), eq(List.of(EXPECTED_KEY)),
            eq(SESSION_ID), eq(SUBSCRIPTION_ID)))
            .thenReturn(List.of("1"));

        assertThat(writer.deleteIfOwner(WATCHER_ID, SESSION_ID, SUBSCRIPTION_ID)).isEmpty();
    }

    @Test
    @DisplayName("deleteIfOwner() 도중 Redis 예외가 나면 격리되어 빈 Optional을 반환한다")
    void deleteIfOwner_isolatesRedisFailure() {
        when(stringRedisTemplate.execute(anyScript(), eq(List.of(EXPECTED_KEY)),
            eq(SESSION_ID), eq(SUBSCRIPTION_ID)))
            .thenThrow(new RuntimeException("Redis 연결 끊김"));

        assertThat(writer.deleteIfOwner(WATCHER_ID, SESSION_ID, SUBSCRIPTION_ID)).isEmpty();
    }

    @Test
    @DisplayName("renewIfOwner()는 스크립트가 1을 반환하면 true")
    void renewIfOwner_returnsTrue_whenScriptReturnsOne() {
        when(stringRedisTemplate.execute(anyScript(), eq(List.of(EXPECTED_KEY)),
            eq(SESSION_ID), eq(SUBSCRIPTION_ID), eq("60000")))
            .thenReturn(1L);

        assertThat(writer.renewIfOwner(WATCHER_ID, SESSION_ID, SUBSCRIPTION_ID, Duration.ofSeconds(60))).isTrue();
    }

    @Test
    @DisplayName("renewIfOwner()는 소유권 불일치(0)일 때 false")
    void renewIfOwner_returnsFalse_whenOwnerMismatch() {
        when(stringRedisTemplate.execute(anyScript(), eq(List.of(EXPECTED_KEY)),
            eq(SESSION_ID), eq(SUBSCRIPTION_ID), eq("60000")))
            .thenReturn(0L);

        assertThat(writer.renewIfOwner(WATCHER_ID, SESSION_ID, SUBSCRIPTION_ID, Duration.ofSeconds(60))).isFalse();
    }

    @Test
    @DisplayName("renewIfOwner() 도중 Redis 예외가 나면 격리되어 false를 반환한다 (주기 신호라 연결을 끊지 않음)")
    void renewIfOwner_isolatesRedisFailure() {
        when(stringRedisTemplate.execute(anyScript(), eq(List.of(EXPECTED_KEY)),
            eq(SESSION_ID), eq(SUBSCRIPTION_ID), eq("60000")))
            .thenThrow(new RuntimeException("Redis 연결 끊김"));

        assertThat(writer.renewIfOwner(WATCHER_ID, SESSION_ID, SUBSCRIPTION_ID, Duration.ofSeconds(60))).isFalse();
    }

    @Test
    @DisplayName("renewIfOwner()는 스크립트가 null을 반환하면 예외 없이 false (unboxing NPE 방지)")
    void renewIfOwner_returnsFalse_whenScriptReturnsNull() {
        when(stringRedisTemplate.execute(anyScript(), eq(List.of(EXPECTED_KEY)),
            eq(SESSION_ID), eq(SUBSCRIPTION_ID), eq("60000")))
            .thenReturn(null);

        assertThat(writer.renewIfOwner(WATCHER_ID, SESSION_ID, SUBSCRIPTION_ID, Duration.ofSeconds(60)))
            .isFalse();
    }
}
