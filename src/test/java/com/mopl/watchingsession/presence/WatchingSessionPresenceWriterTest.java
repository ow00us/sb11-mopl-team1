package com.mopl.watchingsession.presence;

import static com.mopl.global.util.InstantPrecisionUtils.normalizeToMicros;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.slf4j.LoggerFactory;
import com.mopl.watchingsession.presence.WatchingSessionPresenceWriter.DeletedSnapshot;
import com.mopl.watchingsession.presence.WatchingSessionPresenceWriter.RenewResult;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.RedisCallback;
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

    private Logger writerLogger;
    private Level originalLevel;
    private ListAppender<ILoggingEvent> logAppender;

    @BeforeEach
    void attachLogAppender() {
        writerLogger = (Logger) LoggerFactory.getLogger(WatchingSessionPresenceWriter.class);
        originalLevel = writerLogger.getLevel();
        writerLogger.setLevel(Level.DEBUG);

        logAppender = new ListAppender<>();
        logAppender.start();
        writerLogger.addAppender(logAppender);
    }

    @AfterEach
    void detachLogAppender() {
        writerLogger.detachAppender(logAppender);
        writerLogger.setLevel(originalLevel);
    }

    @Test
    @DisplayName("swap()은 직전 필드를 WatchingPresence로 복원해 반환한다")
    void swap_parsesPreviousPresenceFromScriptResult() {
        Instant previousStartedAt = Instant.parse("2026-08-18T00:00:00Z");
        Instant newStartedAt = Instant.now();
        Instant newSnapshotUpdatedAt = normalizeToMicros(Instant.now());
        Instant previousSnapshotUpdatedAt = newSnapshotUpdatedAt.minusSeconds(3600);
        List<String> previousFields = List.of(
            "snapshotId", SNAPSHOT_ID.toString(),
            "contentId", CONTENT_ID.toString(),
            "sessionId", "old-session",
            "subscriptionId", "old-sub",
            "startedAt", previousStartedAt.toString(),
            "snapshotUpdatedAt", previousSnapshotUpdatedAt.toString());

        when(stringRedisTemplate.execute(anyScript(), eq(List.of(EXPECTED_KEY)),
            eq(SNAPSHOT_ID.toString()), eq(CONTENT_ID.toString()), eq(SESSION_ID), eq(SUBSCRIPTION_ID),
            eq(newStartedAt.toString()), eq(newSnapshotUpdatedAt.toString()),
            eq(WATCHER_ID.toString()), anyString()))   // ttlMillis("60000") 삭제
            .thenReturn(previousFields);

        Optional<WatchingPresence> result = writer.swap(
            WATCHER_ID, SNAPSHOT_ID, CONTENT_ID, SESSION_ID, SUBSCRIPTION_ID, newStartedAt, newSnapshotUpdatedAt, Duration.ofSeconds(60));

        assertThat(result).isPresent();
        assertThat(result.get().watcherId()).isEqualTo(WATCHER_ID);
        assertThat(result.get().snapshotId()).isEqualTo(SNAPSHOT_ID);
        assertThat(result.get().contentId()).isEqualTo(CONTENT_ID);
        assertThat(result.get().sessionId()).isEqualTo("old-session");
        assertThat(result.get().subscriptionId()).isEqualTo("old-sub");
        assertThat(result.get().startedAt()).isEqualTo(previousStartedAt);
        assertThat(result.get().snapshotUpdatedAt()).isEqualTo(previousSnapshotUpdatedAt);
    }

    @Test
    @DisplayName("swap()은 직전 값에 snapshotUpdatedAt 필드가 없으면(구버전 레코드) null로 채워 복원한다")
    void swap_parsesPreviousPresence_withNullSnapshotUpdatedAt_whenFieldMissing() {
        List<String> previousFields = List.of(
            "snapshotId", SNAPSHOT_ID.toString(),
            "contentId", CONTENT_ID.toString(),
            "sessionId", "old-session",
            "subscriptionId", "old-sub",
            "startedAt", Instant.now().toString());
        // snapshotUpdatedAt 필드 없음 - 구버전 시나리오

        when(stringRedisTemplate.execute(anyScript(), eq(List.of(EXPECTED_KEY)),
            any(), any(), any(), any(), any(), any(), any(), any()))
            .thenReturn(previousFields);

        Optional<WatchingPresence> result = writer.swap(
            WATCHER_ID, SNAPSHOT_ID, CONTENT_ID, SESSION_ID, SUBSCRIPTION_ID,
            Instant.now(), Instant.now(), Duration.ofSeconds(60));

        assertThat(result).isPresent();
        assertThat(result.get().snapshotUpdatedAt()).isNull();
    }

    @Test
    @DisplayName("swap()은 직전 소유자가 없으면(빈 배열) 빈 Optional을 반환한다")
    void swap_returnsEmpty_whenNoPreviousOwner() {
        when(stringRedisTemplate.execute(anyScript(), eq(List.of(EXPECTED_KEY)),
            any(), any(), any(), any(), any(), any(), any(), any()))
            .thenReturn(List.of());

        Optional<WatchingPresence> result = writer.swap(
            WATCHER_ID, SNAPSHOT_ID, CONTENT_ID, SESSION_ID, SUBSCRIPTION_ID, Instant.now(), Instant.now(), Duration.ofSeconds(60));

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("swap()은 저장된 필드가 불완전하면(스키마 전환 중 옛 형식) 빈 Optional을 반환한다")
    void swap_returnsEmpty_whenFieldsIncomplete() {
        when(stringRedisTemplate.execute(anyScript(), eq(List.of(EXPECTED_KEY)),
            any(), any(), any(), any(), any(), any(), any(), any()))
            .thenReturn(List.of("sessionId", "old-session"));

        Optional<WatchingPresence> result = writer.swap(
            WATCHER_ID, SNAPSHOT_ID, CONTENT_ID, SESSION_ID, SUBSCRIPTION_ID, Instant.now(), Instant.now(), Duration.ofSeconds(60));

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("swap() 도중 Redis 예외가 나면 호출자에게 그대로 전파된다 (소유권 원본이라 격리하지 않음)")
    void swap_propagatesRedisFailure() {
        when(stringRedisTemplate.execute(anyScript(), eq(List.of(EXPECTED_KEY)),
            any(), any(), any(), any(), any(), any(), any(), any()))
            .thenThrow(new RuntimeException("Redis 연결 끊김"));

        assertThatThrownBy(() -> writer.swap(
            WATCHER_ID, SNAPSHOT_ID, CONTENT_ID, SESSION_ID, SUBSCRIPTION_ID, Instant.now(), Instant.now(), Duration.ofSeconds(60)))
            .isInstanceOf(RuntimeException.class);
    }

    @Test
    @DisplayName("deleteIfOwner()는 소유권이 일치하면 삭제된 presence의 snapshotId를 반환한다")
    void deleteIfOwner_returnsTrue_whenScriptReturnsOne() {
        Instant expectedUpdatedAt = normalizeToMicros(Instant.now());

        when(stringRedisTemplate.execute(anyScript(), eq(List.of(EXPECTED_KEY)),
            eq(SESSION_ID), eq(SUBSCRIPTION_ID), eq(WATCHER_ID.toString())))
            .thenReturn(List.of("1", SNAPSHOT_ID.toString(), expectedUpdatedAt.toString()));

        Optional<DeletedSnapshot> result = writer.deleteIfOwner(WATCHER_ID, SESSION_ID, SUBSCRIPTION_ID);

        assertThat(result).isPresent();
        assertThat(result.get().snapshotId()).isEqualTo(SNAPSHOT_ID);
        assertThat(result.get().snapshotUpdatedAt()).isEqualTo(expectedUpdatedAt);
    }

    @Test
    @DisplayName("deleteIfOwner()는 snapshotUpdatedAt이 빈 문자열(구버전 레코드)이면 null로 채워 반환한다")
    void deleteIfOwner_returnsNullUpdatedAt_whenLegacyRecordHasNoToken() {
        when(stringRedisTemplate.execute(anyScript(), eq(List.of(EXPECTED_KEY)),
            eq(SESSION_ID), eq(SUBSCRIPTION_ID), eq(WATCHER_ID.toString())))
            .thenReturn(List.of("1", SNAPSHOT_ID.toString(), ""));

        Optional<DeletedSnapshot> result = writer.deleteIfOwner(WATCHER_ID, SESSION_ID, SUBSCRIPTION_ID);

        assertThat(result).isPresent();
        assertThat(result.get().snapshotId()).isEqualTo(SNAPSHOT_ID);
        assertThat(result.get().snapshotUpdatedAt()).isNull();
    }

    @Test
    @DisplayName("deleteIfOwner()는 소유권 불일치(0)일 때 빈 Optional을 반환한다")
    void deleteIfOwner_returnsEmpty_whenOwnerMismatch() {
        when(stringRedisTemplate.execute(anyScript(), eq(List.of(EXPECTED_KEY)),
            eq(SESSION_ID), eq(SUBSCRIPTION_ID), eq(WATCHER_ID.toString())))
            .thenReturn(List.of("0", "", ""));

        assertThat(writer.deleteIfOwner(WATCHER_ID, SESSION_ID, SUBSCRIPTION_ID)).isEmpty();
    }

    @Test
    @DisplayName("deleteIfOwner()는 활성 세션 없음(-1)일 때도 빈 Optional을 반환한다")
    void deleteIfOwner_returnsEmpty_whenNoActiveSession() {
        when(stringRedisTemplate.execute(anyScript(), eq(List.of(EXPECTED_KEY)),
            eq(SESSION_ID), eq(SUBSCRIPTION_ID), eq(WATCHER_ID.toString())))
            .thenReturn(List.of("-1", "", ""));

        assertThat(writer.deleteIfOwner(WATCHER_ID, SESSION_ID, SUBSCRIPTION_ID)).isEmpty();
    }

    @Test
    @DisplayName("deleteIfOwner()는 snapshotId 파싱에 실패하면 방어적으로 빈 Optional을 반환한다")
    void deleteIfOwner_returnsEmpty_whenSnapshotIdMissing() {
        when(stringRedisTemplate.execute(anyScript(), eq(List.of(EXPECTED_KEY)),
            eq(SESSION_ID), eq(SUBSCRIPTION_ID), eq(WATCHER_ID.toString())))
            .thenReturn(List.of("1"));

        assertThat(writer.deleteIfOwner(WATCHER_ID, SESSION_ID, SUBSCRIPTION_ID)).isEmpty();
    }

    @Test
    @DisplayName("deleteIfOwner() 도중 Redis 예외가 나면 격리되어 빈 Optional을 반환한다")
    void deleteIfOwner_isolatesRedisFailure() {
        when(stringRedisTemplate.execute(anyScript(), eq(List.of(EXPECTED_KEY)),
            eq(SESSION_ID), eq(SUBSCRIPTION_ID), eq(WATCHER_ID.toString())))
            .thenThrow(new RuntimeException("Redis 연결 끊김"));

        assertThat(writer.deleteIfOwner(WATCHER_ID, SESSION_ID, SUBSCRIPTION_ID)).isEmpty();
    }

    @Test
    @DisplayName("deleteIfOwnerSession()은 sessionId만 일치하면 subscriptionId와 무관하게 삭제된 presence의 snapshotId를 반환한다")
    void deleteIfOwnerSession_returnsTrue_whenSessionMatches() {
        Instant expectedUpdatedAt = normalizeToMicros(Instant.now());
        when(stringRedisTemplate.execute(anyScript(), eq(List.of(EXPECTED_KEY)), eq(SESSION_ID), eq(WATCHER_ID.toString())))
            .thenReturn(List.of("1", SNAPSHOT_ID.toString(), expectedUpdatedAt.toString()));

        Optional<DeletedSnapshot> result = writer.deleteIfOwnerSession(WATCHER_ID, SESSION_ID);

        assertThat(result).isPresent();
        assertThat(result.get().snapshotId()).isEqualTo(SNAPSHOT_ID);
        assertThat(result.get().snapshotUpdatedAt()).isEqualTo(expectedUpdatedAt);
    }

    @Test
    @DisplayName("deleteIfOwnerSession()은 sessionId 불일치(0)일 때 빈 Optional을 반환한다")
    void deleteIfOwnerSession_returnsEmpty_whenSessionMismatch() {
        when(stringRedisTemplate.execute(anyScript(), eq(List.of(EXPECTED_KEY)), eq(SESSION_ID), eq(WATCHER_ID.toString())))
            .thenReturn(List.of("0", "", ""));

        assertThat(writer.deleteIfOwnerSession(WATCHER_ID, SESSION_ID)).isEmpty();
    }

    @Test
    @DisplayName("deleteIfOwnerSession()은 활성 세션 없음(-1)일 때도 빈 Optional을 반환한다")
    void deleteIfOwnerSession_returnsEmpty_whenNoActiveSession() {
        when(stringRedisTemplate.execute(anyScript(), eq(List.of(EXPECTED_KEY)), eq(SESSION_ID), eq(WATCHER_ID.toString())))
            .thenReturn(List.of("-1", ""));

        assertThat(writer.deleteIfOwnerSession(WATCHER_ID, SESSION_ID)).isEmpty();
    }

    @Test
    @DisplayName("deleteIfOwnerSession()은 snapshotId 파싱에 실패하면 방어적으로 빈 Optional을 반환한다")
    void deleteIfOwnerSession_returnsEmpty_whenSnapshotIdMissing() {
        when(stringRedisTemplate.execute(anyScript(), eq(List.of(EXPECTED_KEY)), eq(SESSION_ID), eq(WATCHER_ID.toString())))
            .thenReturn(List.of("1"));

        assertThat(writer.deleteIfOwnerSession(WATCHER_ID, SESSION_ID)).isEmpty();
    }

    @Test
    @DisplayName("deleteIfOwnerSession() 도중 Redis 예외가 나면 격리되어 빈 Optional을 반환한다")
    void deleteIfOwnerSession_isolatesRedisFailure() {
        when(stringRedisTemplate.execute(anyScript(), eq(List.of(EXPECTED_KEY)), eq(SESSION_ID), eq(WATCHER_ID.toString())))
            .thenThrow(new RuntimeException("Redis 연결 끊김"));

        assertThat(writer.deleteIfOwnerSession(WATCHER_ID, SESSION_ID)).isEmpty();
    }

    @Test
    @DisplayName("deleteIfOwner()의 소유권 불일치는 WARN으로 기록된다 (구독 단위 종료)")
    void deleteIfOwner_logsWarn_onOwnerMismatch() {
        when(stringRedisTemplate.execute(anyScript(), eq(List.of(EXPECTED_KEY)),
            eq(SESSION_ID), eq(SUBSCRIPTION_ID), eq(WATCHER_ID.toString())))
            .thenReturn(List.of("0", "", ""));

        writer.deleteIfOwner(WATCHER_ID, SESSION_ID, SUBSCRIPTION_ID);

        assertThat(logAppender.list)
            .anySatisfy(event -> {
                assertThat(event.getLevel()).isEqualTo(Level.WARN);
                assertThat(event.getFormattedMessage()).contains("소유권 불일치");
            });
    }

    @Test
    @DisplayName("deleteIfOwnerSession()의 소유권 불일치는 DEBUG로 기록된다 (연결 단위 종료)")
    void deleteIfOwnerSession_logsDebug_onOwnerMismatch() {
        when(stringRedisTemplate.execute(anyScript(), eq(List.of(EXPECTED_KEY)),
            eq(SESSION_ID), eq(WATCHER_ID.toString())))
            .thenReturn(List.of("0", "", ""));

        writer.deleteIfOwnerSession(WATCHER_ID, SESSION_ID);

        assertThat(logAppender.list)
            .noneSatisfy(event -> assertThat(event.getLevel()).isEqualTo(Level.WARN));
        assertThat(logAppender.list)
            .anySatisfy(event -> assertThat(event.getLevel()).isEqualTo(Level.DEBUG));
    }

    @Test
    @DisplayName("빈 응답은 ERROR로 기록된다")
    void deleteIfOwner_logsError_onEmptyResponse() {
        when(stringRedisTemplate.execute(anyScript(), eq(List.of(EXPECTED_KEY)),
            eq(SESSION_ID), eq(SUBSCRIPTION_ID), eq(WATCHER_ID.toString())))
            .thenReturn(List.of());

        writer.deleteIfOwner(WATCHER_ID, SESSION_ID, SUBSCRIPTION_ID);

        assertThat(logAppender.list)
            .anySatisfy(event -> {
                assertThat(event.getLevel()).isEqualTo(Level.ERROR);
                assertThat(event.getFormattedMessage()).contains("빈 응답");
            });
    }

    @Test
    @DisplayName("필드 누락(응답 길이 부족)은 ERROR로 기록된다")
    void deleteIfOwner_logsError_onMissingFields() {
        when(stringRedisTemplate.execute(anyScript(), eq(List.of(EXPECTED_KEY)),
            eq(SESSION_ID), eq(SUBSCRIPTION_ID), eq(WATCHER_ID.toString())))
            .thenReturn(List.of("1", SNAPSHOT_ID.toString())); // snapshotUpdatedAt 필드 누락

        writer.deleteIfOwner(WATCHER_ID, SESSION_ID, SUBSCRIPTION_ID);

        assertThat(logAppender.list)
            .anySatisfy(event -> assertThat(event.getLevel()).isEqualTo(Level.ERROR));
    }

    @Test
    @DisplayName("renewIfOwner()는 스크립트가 1을 반환하면 RENEWED")
    void renewIfOwner_returnsRenewed_whenScriptReturnsOne() {
        when(stringRedisTemplate.execute(anyScript(), eq(List.of(EXPECTED_KEY)),
            eq(SESSION_ID), eq(SUBSCRIPTION_ID), eq(WATCHER_ID.toString()), anyString()))
            .thenReturn(1L);

        assertThat(writer.renewIfOwner(WATCHER_ID, SESSION_ID, SUBSCRIPTION_ID, Duration.ofSeconds(60)))
            .isEqualTo(RenewResult.RENEWED);
    }

    @Test
    @DisplayName("renewIfOwner()는 소유권 불일치(0)일 때 OWNER_MISMATCH")
    void renewIfOwner_returnsOwnerMismatch_whenScriptReturnsZero() {
        when(stringRedisTemplate.execute(anyScript(), eq(List.of(EXPECTED_KEY)),
            eq(SESSION_ID), eq(SUBSCRIPTION_ID), eq(WATCHER_ID.toString()), anyString()))
            .thenReturn(0L);

        assertThat(writer.renewIfOwner(WATCHER_ID, SESSION_ID, SUBSCRIPTION_ID, Duration.ofSeconds(60)))
            .isEqualTo(RenewResult.OWNER_MISMATCH);
    }

    @Test
    @DisplayName("renewIfOwner()는 키 없음(-1)일 때 KEY_MISSING")
    void renewIfOwner_returnsKeyMissing_whenScriptReturnsMinusOne() {
        when(stringRedisTemplate.execute(anyScript(), eq(List.of(EXPECTED_KEY)),
            eq(SESSION_ID), eq(SUBSCRIPTION_ID), eq(WATCHER_ID.toString()), anyString()))
            .thenReturn(-1L);

        assertThat(writer.renewIfOwner(WATCHER_ID, SESSION_ID, SUBSCRIPTION_ID, Duration.ofSeconds(60)))
            .isEqualTo(RenewResult.KEY_MISSING);
    }


    @Test
    @DisplayName("renewIfOwner() 도중 Redis 예외가 나면 격리되어 FAILED (주기 신호라 연결을 끊지 않음)")
    void renewIfOwner_returnsFailed_whenRedisThrows() {
        when(stringRedisTemplate.execute(anyScript(), eq(List.of(EXPECTED_KEY)),
            eq(SESSION_ID), eq(SUBSCRIPTION_ID), eq(WATCHER_ID.toString()), anyString()))
            .thenThrow(new RuntimeException("Redis 연결 끊김"));

        assertThat(writer.renewIfOwner(WATCHER_ID, SESSION_ID, SUBSCRIPTION_ID, Duration.ofSeconds(60)))
            .isEqualTo(RenewResult.FAILED);
    }

    @Test
    @DisplayName("renewIfOwner()는 스크립트가 null을 반환하면 예외 없이 FAILED (unboxing NPE 방지)")
    void renewIfOwner_returnsFailed_whenScriptReturnsNull() {
        when(stringRedisTemplate.execute(anyScript(), eq(List.of(EXPECTED_KEY)),
            eq(SESSION_ID), eq(SUBSCRIPTION_ID), eq(WATCHER_ID.toString()), anyString()))
            .thenReturn(null);

        assertThat(writer.renewIfOwner(WATCHER_ID, SESSION_ID, SUBSCRIPTION_ID, Duration.ofSeconds(60)))
            .isEqualTo(RenewResult.FAILED);
    }

    @Test
    @DisplayName("updateSnapshotIdIfOwner()는 스크립트가 1을 반환하면 true")
    void updateSnapshotIdIfOwner_returnsTrue_whenScriptReturnsOne() {
        UUID newSnapshotId = UUID.randomUUID();
        Instant newSnapshotUpdatedAt = normalizeToMicros(Instant.now());
        when(stringRedisTemplate.execute(anyScript(), eq(List.of(EXPECTED_KEY)),
            eq(SESSION_ID), eq(SUBSCRIPTION_ID), eq(newSnapshotId.toString()), eq(newSnapshotUpdatedAt.toString())))
            .thenReturn(1L);

        assertThat(writer.updateSnapshotIdIfOwner(WATCHER_ID, SESSION_ID, SUBSCRIPTION_ID, newSnapshotId, newSnapshotUpdatedAt)).isTrue();
    }

    @Test
    @DisplayName("renewIfOwner()는 규약 밖의 값을 반환하면 방어적으로 FAILED")
    void renewIfOwner_returnsFailed_whenScriptReturnsUnexpectedCode() {
        when(stringRedisTemplate.execute(anyScript(), eq(List.of(EXPECTED_KEY)),
            eq(SESSION_ID), eq(SUBSCRIPTION_ID), eq(WATCHER_ID.toString()), anyString()))
            .thenReturn(2L);

        assertThat(writer.renewIfOwner(WATCHER_ID, SESSION_ID, SUBSCRIPTION_ID, Duration.ofSeconds(60)))
            .isEqualTo(RenewResult.FAILED);
    }

    @Test
    @DisplayName("updateSnapshotIdIfOwner()는 소유권 불일치(0)일 때 false")
    void updateSnapshotIdIfOwner_returnsFalse_whenOwnerMismatch() {
        UUID newSnapshotId = UUID.randomUUID();
        Instant newSnapshotUpdatedAt = normalizeToMicros(Instant.now());
        when(stringRedisTemplate.execute(anyScript(), eq(List.of(EXPECTED_KEY)),
            eq(SESSION_ID), eq(SUBSCRIPTION_ID), eq(newSnapshotId.toString()), eq(newSnapshotUpdatedAt.toString())))
            .thenReturn(0L);

        assertThat(writer.updateSnapshotIdIfOwner(WATCHER_ID, SESSION_ID, SUBSCRIPTION_ID, newSnapshotId, newSnapshotUpdatedAt)).isFalse();
    }

    @Test
    @DisplayName("updateSnapshotIdIfOwner() 도중 Redis 예외가 나면 격리되어 false를 반환한다")
    void updateSnapshotIdIfOwner_isolatesRedisFailure() {
        UUID newSnapshotId = UUID.randomUUID();
        Instant newSnapshotUpdatedAt = normalizeToMicros(Instant.now());
        when(stringRedisTemplate.execute(anyScript(), eq(List.of(EXPECTED_KEY)),
            eq(SESSION_ID), eq(SUBSCRIPTION_ID), eq(newSnapshotId.toString()), eq(newSnapshotUpdatedAt.toString())))
            .thenThrow(new RuntimeException("Redis 연결 끊김"));

        assertThat(writer.updateSnapshotIdIfOwner(WATCHER_ID, SESSION_ID, SUBSCRIPTION_ID, newSnapshotId, newSnapshotUpdatedAt)).isFalse();
    }

    @Test
    @DisplayName("updateSnapshotIdIfOwner()는 스크립트가 null을 반환하면 예외 없이 false")
    void updateSnapshotIdIfOwner_returnsFalse_whenScriptReturnsNull() {
        UUID newSnapshotId = UUID.randomUUID();
        Instant newSnapshotUpdatedAt = normalizeToMicros(Instant.now());
        when(stringRedisTemplate.execute(anyScript(), eq(List.of(EXPECTED_KEY)),
            eq(SESSION_ID), eq(SUBSCRIPTION_ID), eq(newSnapshotId.toString()), eq(newSnapshotUpdatedAt.toString())))
            .thenReturn(null);

        assertThat(writer.updateSnapshotIdIfOwner(WATCHER_ID, SESSION_ID, SUBSCRIPTION_ID, newSnapshotId, newSnapshotUpdatedAt)).isFalse();
    }

    @Test
    @DisplayName("findExistingWatcherIds()는 파이프라인 결과를 순서대로 매핑해 존재하는 watcherId만 반환한다")
    void findExistingWatcherIds_returnsOnlyExistingOnes_inPipelineOrder() {
        UUID w1 = UUID.randomUUID();
        UUID w2 = UUID.randomUUID();
        UUID w3 = UUID.randomUUID();
        when(stringRedisTemplate.executePipelined(any(RedisCallback.class)))
            .thenReturn(List.of(true, false, true));

        Set<UUID> result = writer.findExistingWatcherIds(List.of(w1, w2, w3));

        assertThat(result).containsExactlyInAnyOrder(w1, w3);
    }

    @Test
    @DisplayName("findExistingWatcherIds()는 빈 입력에 대해 Redis를 호출하지 않고 빈 Set을 반환한다")
    void findExistingWatcherIds_returnsEmptySet_withoutRedisCall_whenInputEmpty() {
        Set<UUID> result = writer.findExistingWatcherIds(List.of());

        assertThat(result).isEmpty();
        verify(stringRedisTemplate, never()).executePipelined(any(RedisCallback.class));
    }

    @Test
    @DisplayName("findExistingWatcherIds() 도중 Redis 예외가 나면 격리되어 전체를 '존재함'으로 보수적으로 반환한다")
    void findExistingWatcherIds_treatsAllAsExisting_whenRedisFails() {
        UUID w1 = UUID.randomUUID();
        UUID w2 = UUID.randomUUID();
        when(stringRedisTemplate.executePipelined(any(RedisCallback.class)))
            .thenThrow(new RuntimeException("Redis 연결 끊김"));

        Set<UUID> result = writer.findExistingWatcherIds(List.of(w1, w2));

        assertThat(result).containsExactlyInAnyOrder(w1, w2);
    }
}
