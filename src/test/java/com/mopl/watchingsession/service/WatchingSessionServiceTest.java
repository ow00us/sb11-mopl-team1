package com.mopl.watchingsession.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.mopl.content.entity.Content;
import com.mopl.content.entity.ContentType;
import com.mopl.content.repository.ContentRepository;
import com.mopl.global.common.CursorResponse;
import com.mopl.global.exception.BusinessException;
import com.mopl.global.exception.ErrorCode;
import com.mopl.global.util.CursorUtils;
import com.mopl.user.entity.User;
import com.mopl.user.repository.UserRepository;
import com.mopl.watchingsession.config.WatchingSessionProperties;
import com.mopl.watchingsession.dto.WatchingSessionDto;
import com.mopl.watchingsession.entity.WatchingSessionSnapshot;
import com.mopl.watchingsession.presence.ContentExistenceCache;
import com.mopl.watchingsession.presence.WatchingPresence;
import com.mopl.watchingsession.presence.WatchingSessionPresenceWriter;
import com.mopl.watchingsession.repository.WatchingSessionSnapshotRepository;
import com.mopl.watchingsession.service.WatchingSessionSnapshotWriter.UpsertResult;
import java.math.BigDecimal;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;

@MockitoSettings(strictness = Strictness.LENIENT)
@ExtendWith(MockitoExtension.class)
public class WatchingSessionServiceTest {

    @Mock
    WatchingSessionSnapshotRepository watchingSessionSnapshotRepository;

    @Mock
    ContentRepository contentRepository;

    @Mock
    ContentExistenceCache contentExistenceCache;

    @Mock
    WatchingSessionSnapshotWriter watchingSessionSnapshotWriter;

    @Mock
    WatchingSessionPresenceWriter watchingSessionPresenceWriter;

    @Mock
    UserRepository userRepository;

    private WatchingSessionService watchingSessionService;

    // Lua 스크립트와 동일한 소유권 의미론을 가진 상태 저장소
    private final Map<UUID, WatchingPresence> presenceStore = new HashMap<>();

    private static final UUID SNAPSHOT_ID = UUID.fromString("00000000-0000-0000-0000-000000000000");
    private static final UUID WATCHER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID CONTENT_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID NEW_CONTENT_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final UUID THIRD_CONTENT_ID = UUID.fromString("44444444-4444-4444-4444-444444444444");
    private static final Instant FIRST_CREATED_AT = Instant.parse("2026-07-29T10:00:00Z");
    private static final String SESSION_ID = "session-123";
    private static final String OTHER_SESSION_ID = "session-999";
    private static final String SUBSCRIPTION_ID = "sub-1";
    private static final String OTHER_SUBSCRIPTION_ID = "sub-2";

    @BeforeEach
    void setUp() {
        presenceStore.clear();
        WatchingSessionProperties watchingSessionProperties = new WatchingSessionProperties();
        watchingSessionProperties.setSessionTtl(Duration.ofMinutes(3));
        watchingSessionProperties.setPresenceTtl(Duration.ofSeconds(60));

        watchingSessionService = new WatchingSessionService(
            watchingSessionProperties, watchingSessionSnapshotRepository, contentRepository,
            userRepository, watchingSessionSnapshotWriter, watchingSessionPresenceWriter, contentExistenceCache);

        when(watchingSessionSnapshotWriter.deleteById(any(), any(), any())).thenReturn(1);

        when(watchingSessionPresenceWriter.swap(any(), any(), any(), any(), any(), any(), any(), any()))
            .thenAnswer(invocation -> {
                UUID watcherId = invocation.getArgument(0);
                WatchingPresence next = new WatchingPresence(
                    invocation.getArgument(1), watcherId, invocation.getArgument(2),
                    invocation.getArgument(3), invocation.getArgument(4), invocation.getArgument(5), invocation.getArgument(6));
                WatchingPresence previous = presenceStore.put(watcherId, next);
                return Optional.ofNullable(previous);
            });

        when(watchingSessionPresenceWriter.deleteIfOwner(any(), any(), any()))
            .thenAnswer(invocation -> {
                UUID watcherId = invocation.getArgument(0);
                String sessionId = invocation.getArgument(1);
                String subscriptionId = invocation.getArgument(2);
                WatchingPresence current = presenceStore.get(watcherId);
                if (current == null
                    || !current.sessionId().equals(sessionId)
                    || !Objects.equals(current.subscriptionId(), subscriptionId)) {
                    return Optional.empty();
                }
                presenceStore.remove(watcherId);
                return Optional.of(new WatchingSessionPresenceWriter.DeletedSnapshot(
                    current.snapshotId(), current.snapshotUpdatedAt()));
            });

        when(watchingSessionPresenceWriter.deleteIfOwnerSession(any(), any()))
            .thenAnswer(invocation -> {
                UUID watcherId = invocation.getArgument(0);
                String sessionId = invocation.getArgument(1);
                WatchingPresence current = presenceStore.get(watcherId);
                if (current == null || !current.sessionId().equals(sessionId)) {
                    return Optional.empty();
                }
                presenceStore.remove(watcherId);
                return Optional.of(new WatchingSessionPresenceWriter.DeletedSnapshot(
                    current.snapshotId(), current.snapshotUpdatedAt()));
            });

        when(watchingSessionPresenceWriter.renewIfOwner(any(), any(), any(), any()))
            .thenAnswer(invocation -> {
                UUID watcherId = invocation.getArgument(0);
                String sessionId = invocation.getArgument(1);
                String subscriptionId = invocation.getArgument(2);
                WatchingPresence current = presenceStore.get(watcherId);
                if (current == null) {
                    return false;
                }
                return current.sessionId().equals(sessionId)
                    && Objects.equals(current.subscriptionId(), subscriptionId);
            });
    }

    // --- Fixture Helpers ---

    // Content 도메인 전용 헬퍼
    private void mockContentExists(UUID contentId) {
        Content mockContent = mock(Content.class);
        when(mockContent.getId()).thenReturn(contentId);
        when(mockContent.getType()).thenReturn(ContentType.MOVIE);
        when(mockContent.getAverageRating()).thenReturn(BigDecimal.ZERO);
        when(mockContent.getReviewCount()).thenReturn(0L);

        when(contentExistenceCache.exists(contentId)).thenReturn(true);
        when(contentRepository.findById(contentId)).thenReturn(Optional.of(mockContent));
    }

    // User 도메인 전용 헬퍼
    private void mockUserExists(UUID userId) {
        User mockUser = mock(User.class);
        when(mockUser.getId()).thenReturn(userId);

        when(userRepository.findById(userId)).thenReturn(Optional.of(mockUser));
        when(userRepository.findAllById(any())).thenReturn(List.of(mockUser));
    }

    private static DataIntegrityViolationException duplicateKeyViolation() {
        return new DataIntegrityViolationException(
            "동시 삽입 충돌", new SQLException("중복", "23505"));
    }

    private WatchingSessionSnapshot createSnapshotFixture(UUID id, UUID watcherId, UUID contentId, Instant createdAt, Instant updatedAt, Instant expiresAt) {
        WatchingSessionSnapshot snapshot = WatchingSessionSnapshot.builder()
            .watcherId(watcherId)
            .contentId(contentId)
            .expiresAt(expiresAt)
            .build();

        ReflectionTestUtils.setField(snapshot, "id", id);
        ReflectionTestUtils.setField(snapshot, "createdAt", createdAt);
        ReflectionTestUtils.setField(snapshot, "updatedAt", updatedAt);

        return snapshot;
    }

    private WatchingSessionSnapshot createSnapshotFixture(UUID contentId, Instant createdAt, Instant updatedAt, Instant expiresAt) {
        return createSnapshotFixture(SNAPSHOT_ID, WATCHER_ID, contentId, createdAt, updatedAt, expiresAt);
    }

    private void mockUpsert(UUID contentId, Instant createdAt, boolean isNewIdentity) {
        WatchingSessionSnapshot snapshot = createSnapshotFixture(contentId, createdAt, createdAt, createdAt.plus(1, ChronoUnit.HOURS));
        when(watchingSessionSnapshotWriter.upsert(eq(WATCHER_ID), eq(contentId), any()))
            .thenReturn(new UpsertResult(snapshot, isNewIdentity));
    }

    /* --- start() 메서드 검증 --- */
    @Test
    @DisplayName("첫 구독은 이전 세션 없이 시작하고, presence에 새 소유자를 기록한다")
    void start_success_firstSubscription_hasNoPrevious() {
        mockContentExists(CONTENT_ID);
        mockUserExists(WATCHER_ID);
        mockUpsert(CONTENT_ID, FIRST_CREATED_AT, true);

        WatchingSessionService.ReplacedSession replaced =
            watchingSessionService.start(WATCHER_ID, CONTENT_ID, SESSION_ID, SUBSCRIPTION_ID);

        assertThat(replaced.previous()).isNull();
        assertThat(replaced.session().content().id()).isEqualTo(CONTENT_ID);
        assertThat(presenceStore.get(WATCHER_ID).sessionId()).isEqualTo(SESSION_ID);
    }

    @Test
    @DisplayName("연속 재구독(A->B->C)에서 각 start()는 자신이 밀어낸 직전 세션만 반환한다")
    void start_success_chainedResubscribe_returnsExactlyOnePreviousEach() {
        mockContentExists(CONTENT_ID);
        mockUserExists(WATCHER_ID);
        mockUpsert(CONTENT_ID, FIRST_CREATED_AT, true);

        WatchingSessionService.ReplacedSession a =
            watchingSessionService.start(WATCHER_ID, CONTENT_ID, SESSION_ID, "sub-A");
        WatchingSessionService.ReplacedSession b =
            watchingSessionService.start(WATCHER_ID, CONTENT_ID, SESSION_ID, "sub-B");
        WatchingSessionService.ReplacedSession c =
            watchingSessionService.start(WATCHER_ID, CONTENT_ID, SESSION_ID, "sub-C");

        assertThat(a.previous()).isNull();
        assertThat(b.previous()).isNotNull(); // A를 밀어냄
        assertThat(c.previous()).isNotNull(); // B를 밀어냄 (A가 다시 나오지 않음)
    }

    @Test
    @DisplayName("연속 재구독(A→B→C, 서로 다른 콘텐츠)에서 각 start()는 자신이 밀어낸 직전 콘텐츠만 정확히 반환한다")
    void start_success_chainedResubscribeAcrossDifferentContents_returnsCorrectPreviousContentEach() {
        mockContentExists(CONTENT_ID);
        mockContentExists(NEW_CONTENT_ID);
        mockContentExists(THIRD_CONTENT_ID);
        mockUserExists(WATCHER_ID);

        mockUpsert(CONTENT_ID, FIRST_CREATED_AT, true);
        WatchingSessionService.ReplacedSession onA =
            watchingSessionService.start(WATCHER_ID, CONTENT_ID, SESSION_ID, "sub-A");

        mockUpsert(NEW_CONTENT_ID, Instant.now(), true);
        WatchingSessionService.ReplacedSession onB =
            watchingSessionService.start(WATCHER_ID, NEW_CONTENT_ID, SESSION_ID, "sub-B");

        mockUpsert(THIRD_CONTENT_ID, Instant.now(), true);
        WatchingSessionService.ReplacedSession onC =
            watchingSessionService.start(WATCHER_ID, THIRD_CONTENT_ID, SESSION_ID, "sub-C");

        assertThat(onA.previous()).isNull();
        assertThat(onB.previous().content().id()).isEqualTo(CONTENT_ID);       // B가 밀어낸 건 A
        assertThat(onC.previous().content().id()).isEqualTo(NEW_CONTENT_ID);   // C가 밀어낸 건 B, A가 아님
    }

    @Test
    @DisplayName("동일 콘텐츠 재구독 도중 presence swap이 실패해도 직전 세션의 DB 행은 삭제되지 않는다")
    void start_keepsDbRow_whenSwapFailsDuringRefreshOfSameContent() {
        mockContentExists(CONTENT_ID);
        mockUserExists(WATCHER_ID);
        mockUpsert(CONTENT_ID, FIRST_CREATED_AT, true);
        watchingSessionService.start(WATCHER_ID, CONTENT_ID, SESSION_ID, SUBSCRIPTION_ID);

        // 동일 콘텐츠 refresh -> isNewIdentity=false
        mockUpsert(CONTENT_ID, FIRST_CREATED_AT, false);
        when(watchingSessionPresenceWriter.swap(any(), any(), any(), any(), any(), any(), any(), any()))
            .thenThrow(new RuntimeException("Redis 연결 끊김"));

        assertThatThrownBy(() ->
            watchingSessionService.start(WATCHER_ID, CONTENT_ID, SESSION_ID, OTHER_SUBSCRIPTION_ID))
            .isInstanceOf(RuntimeException.class);

        verify(watchingSessionSnapshotWriter, never()).deleteById(any(), any(), any());
    }

    @Test
    @DisplayName("신규 삽입 도중 presence swap이 실패하면 방금 만든 DB 행을 보상 삭제한다")
    void start_deletesDbRow_whenSwapFailsDuringNewInsert() {
        mockContentExists(CONTENT_ID);
        mockUserExists(WATCHER_ID);
        Instant updatedAtWithNanos = Instant.parse("2026-08-19T00:00:00.123456789Z");
        WatchingSessionSnapshot snapshot = createSnapshotFixture(
            CONTENT_ID, FIRST_CREATED_AT, updatedAtWithNanos, updatedAtWithNanos.plus(1, ChronoUnit.HOURS));
        when(watchingSessionSnapshotWriter.upsert(eq(WATCHER_ID), eq(CONTENT_ID), any()))
            .thenReturn(new UpsertResult(snapshot, true));
        when(watchingSessionPresenceWriter.swap(any(), any(), any(), any(), any(), any(), any(), any()))
            .thenThrow(new RuntimeException("Redis 연결 끊김"));

        assertThatThrownBy(() ->
            watchingSessionService.start(WATCHER_ID, CONTENT_ID, SESSION_ID, SUBSCRIPTION_ID))
            .isInstanceOf(RuntimeException.class);

        verify(watchingSessionSnapshotWriter).deleteById(
            eq(WATCHER_ID), eq(SNAPSHOT_ID), eq(Instant.parse("2026-08-19T00:00:00.123457Z")));
    }

    @Test
    @DisplayName("콘텐츠가 존재하지 않으면 임계 구역에 들어가기 전에 실패하고 upsert를 호출하지 않는다")
    void start_failure_beforeCriticalSection_whenContentMissing() {
        when(contentExistenceCache.exists(CONTENT_ID)).thenReturn(false);

        assertThatThrownBy(() -> watchingSessionService.start(WATCHER_ID, CONTENT_ID, SESSION_ID, SUBSCRIPTION_ID))
            .isInstanceOf(BusinessException.class);

        verify(watchingSessionSnapshotWriter, never()).upsert(any(), any(), any());
    }

    @Test
    @DisplayName("enrich 실패로 보상 삭제(end)가 소유권 일치로 성공하면 endedPrevious를 담아 던진다")
    void start_throwsWithEndedPrevious_whenEnrichFailsAfterCompensation() {
        mockContentExists(CONTENT_ID);
        mockUpsert(CONTENT_ID, FIRST_CREATED_AT, true);
        when(userRepository.findById(WATCHER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> watchingSessionService.start(WATCHER_ID, CONTENT_ID, SESSION_ID, SUBSCRIPTION_ID))
            .isInstanceOf(WatchingSessionService.StartFailedException.class);

        assertThat(presenceStore).doesNotContainKey(WATCHER_ID); // end()가 보상 삭제까지 완료
    }

    @Test
    @DisplayName("start()에서 upsert 중복키 충돌이 1회 발생해도 재시도로 세션이 정상 시작된다")
    void start_recovers_whenUpsertConflictsOnceThenSucceeds() {
        mockContentExists(CONTENT_ID);
        UpsertResult successResult = new UpsertResult(
            createSnapshotFixture(SNAPSHOT_ID, WATCHER_ID, CONTENT_ID, FIRST_CREATED_AT, FIRST_CREATED_AT,
                FIRST_CREATED_AT.plus(1, ChronoUnit.HOURS)), true);
        when(watchingSessionSnapshotWriter.upsert(eq(WATCHER_ID), eq(CONTENT_ID), any()))
            .thenThrow(duplicateKeyViolation())
            .thenReturn(successResult);
        mockUserExists(WATCHER_ID);

        WatchingSessionService.ReplacedSession result =
            watchingSessionService.start(WATCHER_ID, CONTENT_ID, SESSION_ID, SUBSCRIPTION_ID);

        assertThat(result.session()).isNotNull();
        verify(watchingSessionSnapshotWriter, times(2)).upsert(eq(WATCHER_ID), eq(CONTENT_ID), any());
    }

    @Test
    @DisplayName("presence에 기록되는 세대 토큰은 나노초 성분이 있어도 마이크로초로 정규화돼 DB 값과 일치한다")
    void start_normalizesSnapshotUpdatedAt_toMicrosBeforeStoringInPresence() {
        mockContentExists(CONTENT_ID);
        mockUserExists(WATCHER_ID);

        Instant updatedAtWithNanos = Instant.parse("2026-08-19T00:00:00.123456789Z");
        WatchingSessionSnapshot snapshot = createSnapshotFixture(
            CONTENT_ID, FIRST_CREATED_AT, updatedAtWithNanos, updatedAtWithNanos.plus(1, ChronoUnit.HOURS));
        when(watchingSessionSnapshotWriter.upsert(eq(WATCHER_ID), eq(CONTENT_ID), any()))
            .thenReturn(new UpsertResult(snapshot, true));

        watchingSessionService.start(WATCHER_ID, CONTENT_ID, SESSION_ID, SUBSCRIPTION_ID);

        ArgumentCaptor<Instant> tokenCaptor = ArgumentCaptor.forClass(Instant.class);
        verify(watchingSessionPresenceWriter).swap(
            eq(WATCHER_ID), eq(SNAPSHOT_ID), eq(CONTENT_ID), eq(SESSION_ID), eq(SUBSCRIPTION_ID),
            any(), tokenCaptor.capture(), any());

        // plusNanos(500) 반올림 규약: .123456789 + 500ns = .123457289 -> 마이크로초 절삭 -> .123457
        assertThat(tokenCaptor.getValue()).isEqualTo(Instant.parse("2026-08-19T00:00:00.123457Z"));
        assertThat(tokenCaptor.getValue()).isNotEqualTo(updatedAtWithNanos);
    }

    @Test
    @DisplayName("신규 삽입 직후 presence swap이 실패하면, 나노초를 포함한 메모리 상 updatedAt이 정규화된 값으로 보상 삭제된다")
    void start_normalizesUpdatedAt_beforeCompensatingDeleteOnSwapFailure() {
        mockContentExists(CONTENT_ID);
        mockUserExists(WATCHER_ID);

        Instant updatedAtWithNanos = Instant.parse("2026-08-19T00:00:00.123456789Z");
        WatchingSessionSnapshot snapshot = createSnapshotFixture(
            CONTENT_ID, FIRST_CREATED_AT, updatedAtWithNanos, updatedAtWithNanos.plus(1, ChronoUnit.HOURS));
        when(watchingSessionSnapshotWriter.upsert(eq(WATCHER_ID), eq(CONTENT_ID), any()))
            .thenReturn(new UpsertResult(snapshot, true));
        when(watchingSessionPresenceWriter.swap(any(), any(), any(), any(), any(), any(), any(), any()))
            .thenThrow(new RuntimeException("Redis 연결 끊김"));

        assertThatThrownBy(() ->
            watchingSessionService.start(WATCHER_ID, CONTENT_ID, SESSION_ID, SUBSCRIPTION_ID))
            .isInstanceOf(RuntimeException.class);

        ArgumentCaptor<Instant> tokenCaptor = ArgumentCaptor.forClass(Instant.class);
        verify(watchingSessionSnapshotWriter).deleteById(eq(WATCHER_ID), eq(SNAPSHOT_ID), tokenCaptor.capture());

        // .789 나노 -> +500 반올림 -> .123457로 정규화된 값이어야 한다 (원본 그대로면 실패)
        assertThat(tokenCaptor.getValue()).isEqualTo(Instant.parse("2026-08-19T00:00:00.123457Z"));
        assertThat(tokenCaptor.getValue()).isNotEqualTo(updatedAtWithNanos);
    }

    /* --- end() 메서드 검증 --- */
    @Test
    @DisplayName("소유권이 일치하면 presence와 DB 스냅샷을 모두 삭제하고 true를 반환한다")
    void end_success_deletesPresenceAndSnapshot_whenOwnershipMatches() {
        mockContentExists(CONTENT_ID);
        mockUserExists(WATCHER_ID);
        mockUpsert(CONTENT_ID, FIRST_CREATED_AT, true);
        watchingSessionService.start(WATCHER_ID, CONTENT_ID, SESSION_ID, SUBSCRIPTION_ID);

        boolean deleted = watchingSessionService.end(WATCHER_ID, SESSION_ID, SUBSCRIPTION_ID);

        assertThat(deleted).isTrue();
        assertThat(presenceStore).doesNotContainKey(WATCHER_ID);
        verify(watchingSessionSnapshotWriter).deleteById(eq(WATCHER_ID), eq(SNAPSHOT_ID), any());
    }

    @Test
    @DisplayName("소유권이 불일치하면(낡은 탭) 아무것도 지우지 않고 false를 반환한다")
    void end_success_returnsFalse_withoutDeletingAnything_whenOwnershipMismatches() {
        mockContentExists(CONTENT_ID);
        mockUserExists(WATCHER_ID);
        mockUpsert(CONTENT_ID, FIRST_CREATED_AT, true);
        watchingSessionService.start(WATCHER_ID, CONTENT_ID, SESSION_ID, OTHER_SUBSCRIPTION_ID);

        boolean deleted = watchingSessionService.end(WATCHER_ID, SESSION_ID, SUBSCRIPTION_ID);

        assertThat(deleted).isFalse();
        assertThat(presenceStore).containsKey(WATCHER_ID); // 현재 소유자의 presence는 그대로
        verify(watchingSessionSnapshotWriter, never()).deleteById(any(), any(), any());
    }

    @Test
    @DisplayName("같은 연결에서 재구독(subscriptionId만 변경) 후, 낡은 subscriptionId의 end 요청은 무시되고 최신 것만 성공한다")
    void end_success_onlyLatestSubscriptionSucceeds_afterSameConnectionResubscribe() {
        mockContentExists(CONTENT_ID);
        mockUserExists(WATCHER_ID);
        mockUpsert(CONTENT_ID, FIRST_CREATED_AT, false);
        watchingSessionService.start(WATCHER_ID, CONTENT_ID, SESSION_ID, SUBSCRIPTION_ID);
        watchingSessionService.start(WATCHER_ID, CONTENT_ID, SESSION_ID, OTHER_SUBSCRIPTION_ID);

        boolean staleEnded = watchingSessionService.end(WATCHER_ID, SESSION_ID, SUBSCRIPTION_ID);
        assertThat(staleEnded).isFalse();

        boolean currentEnded = watchingSessionService.end(WATCHER_ID, SESSION_ID, OTHER_SUBSCRIPTION_ID);
        assertThat(currentEnded).isTrue();
    }

    @Test
    @DisplayName("종료 시 활성 세션(메모리 소유권)이 없으면 false를 반환하고 예외 없이 종료")
    void end_success_returnsFalse_whenNoActiveSession() {
        boolean actuallyDeleted = watchingSessionService.end(WATCHER_ID, SESSION_ID, SUBSCRIPTION_ID);
        assertThat(actuallyDeleted).isFalse();
        verify(watchingSessionSnapshotWriter, never()).deleteById(any(), any(), any());
    }

    /* --- endByConnection() 메서드 검증 --- */
    @Test
    @DisplayName("sessionId가 일치하면 presence와 DB 스냅샷을 모두 삭제하고, 삭제된 스냅샷 기준 DTO를 반환한다")
    void endByConnection_success_deletesPresenceAndSnapshot_returnsDto() {
        mockContentExists(CONTENT_ID);
        mockUserExists(WATCHER_ID);
        mockUpsert(CONTENT_ID, FIRST_CREATED_AT, true);
        watchingSessionService.start(WATCHER_ID, CONTENT_ID, SESSION_ID, SUBSCRIPTION_ID);

        WatchingSessionSnapshot snapshotFixture = createSnapshotFixture(
            CONTENT_ID, FIRST_CREATED_AT, FIRST_CREATED_AT, FIRST_CREATED_AT.plus(1, ChronoUnit.HOURS));
        when(watchingSessionSnapshotRepository.findById(SNAPSHOT_ID)).thenReturn(Optional.of(snapshotFixture));

        Optional<WatchingSessionDto> result = watchingSessionService.endByConnection(WATCHER_ID, SESSION_ID);

        assertThat(result).isPresent();
        assertThat(result.get().id()).isEqualTo(SNAPSHOT_ID);
        assertThat(result.get().content().id()).isEqualTo(CONTENT_ID);
        assertThat(presenceStore).doesNotContainKey(WATCHER_ID);
        verify(watchingSessionSnapshotWriter).deleteById(eq(WATCHER_ID), eq(SNAPSHOT_ID), any());
    }

    @Test
    @DisplayName("sessionId가 불일치하면(다른 연결로 소유권 이전됨) 아무것도 지우지 않고 빈 Optional을 반환한다")
    void endByConnection_returnsEmpty_whenSessionMismatches() {
        mockContentExists(CONTENT_ID);
        mockUserExists(WATCHER_ID);
        mockUpsert(CONTENT_ID, FIRST_CREATED_AT, true);
        watchingSessionService.start(WATCHER_ID, CONTENT_ID, OTHER_SESSION_ID, SUBSCRIPTION_ID);

        Optional<WatchingSessionDto> result = watchingSessionService.endByConnection(WATCHER_ID, SESSION_ID);

        assertThat(result).isEmpty();
        assertThat(presenceStore).containsKey(WATCHER_ID); // 현재 소유자의 presence는 그대로
        verify(watchingSessionSnapshotWriter, never()).deleteById(any(), any(), any());
    }

    @Test
    @DisplayName("presence 소유권은 확인됐지만 DB 스냅샷이 이미 다른 세대로 교체된 경우(0행 삭제) 빈 Optional을 반환하고 유령 LEAVE를 만들지 않는다")
    void endByConnection_returnsEmpty_whenDbRowAlreadyReplacedByAnotherGeneration() {
        mockContentExists(CONTENT_ID);
        mockUserExists(WATCHER_ID);
        mockUpsert(CONTENT_ID, FIRST_CREATED_AT, true);
        watchingSessionService.start(WATCHER_ID, CONTENT_ID, SESSION_ID, SUBSCRIPTION_ID);

        WatchingSessionSnapshot snapshotFixture = createSnapshotFixture(
            CONTENT_ID, FIRST_CREATED_AT, FIRST_CREATED_AT, FIRST_CREATED_AT.plus(1, ChronoUnit.HOURS));
        when(watchingSessionSnapshotRepository.findById(SNAPSHOT_ID)).thenReturn(Optional.of(snapshotFixture));
        // 다른 인스턴스가 이미 이 watcher의 새 세대로 행을 교체해 조건부 삭제가 0행에 그친 상황을 재현
        when(watchingSessionSnapshotWriter.deleteById(eq(WATCHER_ID), eq(SNAPSHOT_ID), any())).thenReturn(0);

        Optional<WatchingSessionDto> result = watchingSessionService.endByConnection(WATCHER_ID, SESSION_ID);

        assertThat(result).isEmpty();
    }

    /* --- get() 메서드 검증 --- */
    @Test
    @DisplayName("활성 세션이 있으면 조회 결과 반환")
    void get_success_whenActiveSessionExists() {
        mockContentExists(CONTENT_ID);
        mockUserExists(WATCHER_ID);
        Instant now = Instant.now();

        WatchingSessionSnapshot snapshot = createSnapshotFixture(
            CONTENT_ID, FIRST_CREATED_AT, now, now.plus(1, ChronoUnit.HOURS)
        );
        when(watchingSessionSnapshotRepository.findByWatcherId(WATCHER_ID)).thenReturn(Optional.of(snapshot));

        Optional<WatchingSessionDto> result = watchingSessionService.get(WATCHER_ID);

        assertThat(result).isPresent();
        WatchingSessionDto dto = result.get();
        assertThat(dto.id()).isEqualTo(SNAPSHOT_ID);
        assertThat(dto.createdAt()).isEqualTo(FIRST_CREATED_AT);
        assertThat(dto.watcher().userId()).isEqualTo(WATCHER_ID);
        assertThat(dto.content().id()).isEqualTo(CONTENT_ID);
    }

    @Test
    @DisplayName("활성 세션이 없으면 빈 Optional 반환")
    void get_success_whenNoActiveSession() {
        Instant now = Instant.now();
        WatchingSessionSnapshot expired = createSnapshotFixture(
            CONTENT_ID, FIRST_CREATED_AT, now, now.minusSeconds(1)
        );
        when(watchingSessionSnapshotRepository.findByWatcherId(WATCHER_ID)).thenReturn(Optional.of(expired));

        Optional<WatchingSessionDto> result = watchingSessionService.get(WATCHER_ID);

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("스냅샷은 있으나 유저가 조회되지 않으면 RESOURCE_NOT_FOUND 예외 발생")
    void get_fail_whenWatcherNotFoundDuringEnrich() {
        Instant now = Instant.now();
        WatchingSessionSnapshot snapshot = createSnapshotFixture(
            CONTENT_ID, FIRST_CREATED_AT, now, now.plus(1, ChronoUnit.HOURS)
        );

        when(watchingSessionSnapshotRepository.findByWatcherId(WATCHER_ID))
            .thenReturn(Optional.of(snapshot));
        when(userRepository.findById(WATCHER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> watchingSessionService.get(WATCHER_ID))
            .isInstanceOf(BusinessException.class)
            .extracting("errorCode")
            .isEqualTo(ErrorCode.RESOURCE_NOT_FOUND);
    }

    /* --- getListByContent() 메서드 검증 --- */
    @Test
    @DisplayName("정상 조회 시 CursorResponse로 변환되어 반환")
    void getListByContent_success_returnsData() {
        mockContentExists(CONTENT_ID);
        mockUserExists(WATCHER_ID);
        Instant now = Instant.now();

        WatchingSessionSnapshot snapshot = createSnapshotFixture(
            CONTENT_ID, FIRST_CREATED_AT, now, now.plus(1, ChronoUnit.HOURS)
        );
        when(watchingSessionSnapshotRepository.findByContentIdFirstPageDesc(
            eq(CONTENT_ID), isNull(), any(), any()))
            .thenReturn(List.of(snapshot));
        when(watchingSessionSnapshotRepository.countByContentId(
            eq(CONTENT_ID), isNull(), any()))
            .thenReturn(1L);

        CursorResponse<WatchingSessionDto> result = watchingSessionService.getListByContent(
            CONTENT_ID, null, null, null, 10, "createdAt", "DESCENDING"
        );

        assertThat(result.data()).hasSize(1);
        assertThat(result.data().get(0).id()).isEqualTo(SNAPSHOT_ID);
        assertThat(result.totalCount()).isEqualTo(1L);
        assertThat(result.hasNext()).isFalse();
        verify(watchingSessionSnapshotRepository, never()).findByContentIdFirstPageAsc(
            any(), any(), any(), any());
    }

    @Test
    @DisplayName("sortDirection=ASCENDING이면 오름차순 조회 메서드를 호출한다")
    void getListByContent_success_ascendingCallsCorrectMethod() {
        mockContentExists(CONTENT_ID);

        when(watchingSessionSnapshotRepository.findByContentIdFirstPageAsc(
            eq(CONTENT_ID), isNull(), any(Instant.class), any()))
            .thenReturn(List.of());
        when(watchingSessionSnapshotRepository.countByContentId(
            eq(CONTENT_ID), isNull(), any()))
            .thenReturn(0L);

        watchingSessionService.getListByContent(
            CONTENT_ID, null, null, null, 10, "createdAt", "ASCENDING"
        );

        verify(watchingSessionSnapshotRepository).findByContentIdFirstPageAsc(
            eq(CONTENT_ID), isNull(), any(Instant.class), any());
        verify(watchingSessionSnapshotRepository, never()).findByContentIdFirstPageDesc(
            any(), any(), any(), any());
    }

    @Test
    @DisplayName("sortDirection=ASCENDING이고 cursor가 있으면 findByContentIdAfterAscending을 호출한다")
    void getListByContent_success_ascendingWithCursorCallsCorrectMethod() {
        mockContentExists(CONTENT_ID);
        UUID idAfter = UUID.randomUUID();
        Instant now= Instant.now();
        String cursor = CursorUtils.encodeInstant(now);

        when(watchingSessionSnapshotRepository.findByContentIdAfterAsc(
            eq(CONTENT_ID), isNull(), any(Instant.class), eq(now), eq(idAfter), any()))
            .thenReturn(List.of());
        when(watchingSessionSnapshotRepository.countByContentId(
            eq(CONTENT_ID), isNull(), any()))
            .thenReturn(0L);

        watchingSessionService.getListByContent(
            CONTENT_ID, null, cursor, idAfter, 10, "createdAt", "ASCENDING"
        );

        verify(watchingSessionSnapshotRepository).findByContentIdAfterAsc(
            eq(CONTENT_ID), isNull(), any(Instant.class), eq(now), eq(idAfter), any());
        verify(watchingSessionSnapshotRepository, never()).findByContentIdAfterDesc(
            any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("시청자가 없으면 빈 목록과 totalCount 0 반환")
    void getListByContent_success_returnsEmptyList() {
        mockContentExists(CONTENT_ID);
        when(watchingSessionSnapshotRepository.findByContentIdFirstPageDesc(
            eq(CONTENT_ID), isNull(), any(), any()))
            .thenReturn(List.of());
        when(watchingSessionSnapshotRepository.countByContentId(
            eq(CONTENT_ID), isNull(), any()))
            .thenReturn(0L);

        CursorResponse<WatchingSessionDto> result = watchingSessionService.getListByContent(
            CONTENT_ID, null, null, null, 10, "createdAt", "DESCENDING"
        );

        assertThat(result.data()).isEmpty();
        assertThat(result.totalCount()).isEqualTo(0L);
        assertThat(result.hasNext()).isFalse();
    }

    @Test
    @DisplayName("watcherNameLike로 필터링된 결과만 반환")
    void getListByContent_success_filtersByWatcherName() {
        mockContentExists(CONTENT_ID);
        mockUserExists(WATCHER_ID);

        Instant now = Instant.now();
        WatchingSessionSnapshot snapshot = createSnapshotFixture(
            CONTENT_ID, FIRST_CREATED_AT, now, now.plus(1, ChronoUnit.HOURS)
        );
        when(watchingSessionSnapshotRepository.findByContentIdFirstPageDesc(
            eq(CONTENT_ID), eq("김"), any(), any()))
            .thenReturn(List.of(snapshot));
        when(watchingSessionSnapshotRepository.countByContentId(
            eq(CONTENT_ID), eq("김"), any()))
            .thenReturn(1L);

        CursorResponse<WatchingSessionDto> result = watchingSessionService.getListByContent(
            CONTENT_ID, "김", null, null, 10, "createdAt", "DESCENDING"
        );

        assertThat(result.data()).hasSize(1);
        assertThat(result.totalCount()).isEqualTo(1L);
        verify(watchingSessionSnapshotRepository).findByContentIdFirstPageDesc(
            eq(CONTENT_ID), eq("김"), any(), any());
        verify(watchingSessionSnapshotRepository).countByContentId(
            eq(CONTENT_ID), eq("김"), any());
    }

    @Test
    @DisplayName("watcherNameLike의 와일드카드 문자가 이스케이프되어 두 쿼리에 동일하게 전달")
    void getListByContent_success_escapesWildcardConsistently() {
        mockContentExists(CONTENT_ID);
        mockUserExists(WATCHER_ID);

        Instant now = Instant.now();
        String rawInput = "50%_off";
        String escapedInput = "50\\%\\_off";

        WatchingSessionSnapshot snapshot = createSnapshotFixture(
            CONTENT_ID, FIRST_CREATED_AT, now, now.plus(1, ChronoUnit.HOURS)
        );
        when(watchingSessionSnapshotRepository.findByContentIdFirstPageDesc(
            eq(CONTENT_ID), eq(escapedInput), any(), any()))
            .thenReturn(List.of(snapshot));
        when(watchingSessionSnapshotRepository.countByContentId(
            eq(CONTENT_ID), eq(escapedInput), any()))
            .thenReturn(1L);

        CursorResponse<WatchingSessionDto> result = watchingSessionService.getListByContent(
            CONTENT_ID, rawInput, null, null, 10, "createdAt", "DESCENDING"
        );

        assertThat(result.data()).hasSize(1);
        assertThat(result.totalCount()).isEqualTo(1L);
        verify(watchingSessionSnapshotRepository).findByContentIdFirstPageDesc(
            eq(CONTENT_ID), eq(escapedInput), any(), any());
        verify(watchingSessionSnapshotRepository).countByContentId(
            eq(CONTENT_ID), eq(escapedInput), any());
    }

    @Test
    @DisplayName("limit보다 많은 데이터가 있으면 hasNest=true와 다음 커서 반환")
    void getListByContent_success_hasNextTrueWithCursor() {
        mockContentExists(CONTENT_ID);
        mockUserExists(WATCHER_ID);
        Instant now = Instant.now();

        WatchingSessionSnapshot s1 = createSnapshotFixture(
            UUID.randomUUID(), WATCHER_ID, CONTENT_ID, FIRST_CREATED_AT, now, now.plus(1, ChronoUnit.HOURS)
        );
        WatchingSessionSnapshot s2 = createSnapshotFixture(
            UUID.randomUUID(), WATCHER_ID, CONTENT_ID, FIRST_CREATED_AT, now.minusSeconds(1), now.plus(1, ChronoUnit.HOURS)
        );

        when(watchingSessionSnapshotRepository.findByContentIdFirstPageDesc(
            eq(CONTENT_ID), isNull(), any(), any()))
            .thenReturn(List.of(s1, s2));
        when(watchingSessionSnapshotRepository.countByContentId(
            eq(CONTENT_ID), isNull(), any()))
            .thenReturn(2L);

        CursorResponse<WatchingSessionDto> result = watchingSessionService.getListByContent(
            CONTENT_ID, null, null, null, 1, "createdAt", "DESCENDING"
        );

        assertThat(result.data()).hasSize(1);
        assertThat(result.hasNext()).isTrue();
        assertThat(result.totalCount()).isEqualTo(2L);
        assertThat(result.nextCursor()).isEqualTo(CursorUtils.encodeInstant(s1.getCreatedAt()));
        assertThat(result.nextIdAfter()).isEqualTo(s1.getId());
        verify(watchingSessionSnapshotRepository).countByContentId(eq(CONTENT_ID), isNull(), any());
    }

    @Test
    @DisplayName("cursor/idAfter로 다음 페이지 조회")
    void getListByContent_success_withCursorMovesToNextPage() {
        mockContentExists(CONTENT_ID);
        mockUserExists(WATCHER_ID);

        Instant now = Instant.now();
        UUID idAfter = UUID.randomUUID();
        String cursor = CursorUtils.encodeInstant(now);

        WatchingSessionSnapshot snapshot = createSnapshotFixture(
            CONTENT_ID, FIRST_CREATED_AT, now.minusSeconds(10), now.plus(1, ChronoUnit.HOURS)
        );
        when(watchingSessionSnapshotRepository.findByContentIdAfterDesc(
            eq(CONTENT_ID), isNull(), any(), eq(now), eq(idAfter), any()))
            .thenReturn(List.of(snapshot));
        when(watchingSessionSnapshotRepository.countByContentId(
            eq(CONTENT_ID), isNull(), any()))
            .thenReturn(1L);

        CursorResponse<WatchingSessionDto> result = watchingSessionService.getListByContent(
            CONTENT_ID, null, cursor, idAfter, 10, "createdAt", "DESCENDING"
        );

        assertThat(result.data()).hasSize(1);
        verify(watchingSessionSnapshotRepository).findByContentIdAfterDesc(
            eq(CONTENT_ID), isNull(), any(), eq(now), eq(idAfter), any());
    }

    @Test
    @DisplayName("만료된 세션은 목록에서 제외")
    void getListByContent_success_excludesExpiredViaQuery() {
        mockContentExists(CONTENT_ID);
        when(watchingSessionSnapshotRepository.findByContentIdFirstPageDesc(
            eq(CONTENT_ID), isNull(), any(Instant.class), any()))
            .thenReturn(List.of());
        when(watchingSessionSnapshotRepository.countByContentId(
            eq(CONTENT_ID), isNull(), any()))
            .thenReturn(0L);

        watchingSessionService.getListByContent(
            CONTENT_ID, null, null, null, 10, "createdAt", "DESCENDING"
        );

        verify(watchingSessionSnapshotRepository).findByContentIdFirstPageDesc(
            eq(CONTENT_ID), isNull(), any(Instant.class), any());
    }

    @Test
    @DisplayName("정상 조회 시 N+1 없이 배치로 사용자를 조회한다")
    void getListByContent_success_batchFetchesWatchers() {
        mockContentExists(CONTENT_ID);
        mockUserExists(WATCHER_ID);

        Instant now = Instant.now();
        WatchingSessionSnapshot snapshot = createSnapshotFixture(
            CONTENT_ID, FIRST_CREATED_AT, now, now.plusSeconds(60)
        );
        when(watchingSessionSnapshotRepository.findByContentIdFirstPageDesc(
            eq(CONTENT_ID), any(), any(), any()))
            .thenReturn(List.of(snapshot));

        when(watchingSessionSnapshotRepository.countByContentId(eq(CONTENT_ID), any(), any())).thenReturn(1L);

        CursorResponse<WatchingSessionDto> result = watchingSessionService.getListByContent(
            CONTENT_ID, null, null, null, 10, "createdAt", "DESCENDING");

        assertThat(result.data()).hasSize(1);
        verify(userRepository).findAllById(List.of(WATCHER_ID));
        verify(userRepository, never()).findById(any());
    }

    @Test
    @DisplayName("존재하지 않는 콘텐츠면 CONTENT_NOT_FOUND 예외 발생")
    void getListByContent_fail_contentNotFound() {
        when(contentRepository.findById(CONTENT_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> watchingSessionService.getListByContent(
            CONTENT_ID, null, null, null, 10, "createdAt", "DESCENDING"
        ))
            .isInstanceOf(BusinessException.class)
            .extracting("errorCode")
            .isEqualTo(ErrorCode.CONTENT_NOT_FOUND);
    }

    @Test
    @DisplayName("sortBy가 createdAt이 아니면 INVALID_INPUT 예외 발생")
    void getListByContent_fail_invalidSortBy() {
        mockContentExists(CONTENT_ID);

        assertThatThrownBy(() -> watchingSessionService.getListByContent(
            CONTENT_ID, null, null, null, 10, "updatedAt", "DESCENDING"
        ))
            .isInstanceOf(BusinessException.class)
            .extracting("errorCode")
            .isEqualTo(ErrorCode.INVALID_INPUT);
    }

    @Test
    @DisplayName("cursor만 있고 idAfter가 없으면 INVALID_INPUT 예외 발생")
    void getListByContent_fail_cursorWithoutIdAfter() {
        mockContentExists(CONTENT_ID);
        Instant now = Instant.now();
        String cursor = CursorUtils.encodeInstant(now);

        assertThatThrownBy(() -> watchingSessionService.getListByContent(
            CONTENT_ID, null, cursor, null, 10, "createdAt", "DESCENDING"
        ))
            .isInstanceOf(BusinessException.class)
            .extracting("errorCode")
            .isEqualTo(ErrorCode.INVALID_INPUT);
    }

    @Test
    @DisplayName("잘못된 cursor 값이 들어오면 INVALID_INPUT 예외 발생")
    void getListByContent_fail_invalidCursor() {
        mockContentExists(CONTENT_ID);

        assertThatThrownBy(() -> watchingSessionService.getListByContent(
            CONTENT_ID, null, "invalid-cursor!!", UUID.randomUUID(), 10, "createdAt", "DESCENDING"))
            .isInstanceOf(BusinessException.class)
            .extracting("errorCode").isEqualTo(ErrorCode.INVALID_INPUT);
    }

    @Test
    @DisplayName("idAfter만 있고 cursor가 없으면 INVALID_INPUT 예외 발생")
    void getListByContent_fail_idAfterWithoutCursor() {
        mockContentExists(CONTENT_ID);

        assertThatThrownBy(() -> watchingSessionService.getListByContent(
            CONTENT_ID, null, null, UUID.randomUUID(), 10, "createdAt", "DESCENDING"
        ))
            .isInstanceOf(BusinessException.class)
            .extracting("errorCode")
            .isEqualTo(ErrorCode.INVALID_INPUT);
    }

    @Test
    @DisplayName("sortDirection이 허용값이 아니면 INVALID_INPUT 예외 발생")
    void getListByContent_fail_invalidSortDirection() {
        mockContentExists(CONTENT_ID);

        assertThatThrownBy(() -> watchingSessionService.getListByContent(
            CONTENT_ID, null, null, null, 10, "createdAt", "WRONG"
        ))
            .isInstanceOf(BusinessException.class)
            .extracting("errorCode")
            .isEqualTo(ErrorCode.INVALID_INPUT);
    }

    @Test
    @DisplayName("요청 sortDirection이 소문자여도 응답은 대문자로 정규화된다")
    void getListByContent_success_normalizesSortDirectionCase() {
        mockContentExists(CONTENT_ID);
        when(watchingSessionSnapshotRepository.findByContentIdFirstPageAsc(
            eq(CONTENT_ID), any(), any(), any()))
            .thenReturn(List.of());
        when(watchingSessionSnapshotRepository.countByContentId(eq(CONTENT_ID), any(), any())).thenReturn(0L);

        CursorResponse<WatchingSessionDto> result = watchingSessionService.getListByContent(
            CONTENT_ID, null, null, null, 10, "createdAt", "ascending");

        assertThat(result.sortDirection()).isEqualTo("ASCENDING");
    }

    /* --- heartbeat() 메서드 검증 --- */

    @Test
    @DisplayName("소유권이 일치하면 presence TTL을 연장하고 DB expiresAt도 갱신한다")
    void heartbeat_renewsPresenceAndDb_whenOwnershipMatches() {
        mockContentExists(CONTENT_ID);
        mockUserExists(WATCHER_ID);
        mockUpsert(CONTENT_ID, FIRST_CREATED_AT, true);
        watchingSessionService.start(WATCHER_ID, CONTENT_ID, SESSION_ID, SUBSCRIPTION_ID);
        when(watchingSessionSnapshotWriter.renewExpiresAt(any(), any(), any())).thenReturn(1);

        watchingSessionService.heartbeat(WATCHER_ID, CONTENT_ID, SESSION_ID, SUBSCRIPTION_ID);

        verify(watchingSessionSnapshotWriter).renewExpiresAt(eq(WATCHER_ID), eq(CONTENT_ID), any());
    }

    @Test
    @DisplayName("소유권이 불일치하면(낡은 탭의 heartbeat) DB를 전혀 건드리지 않는다")
    void heartbeat_skipsDbUpdate_whenOwnershipMismatches() {
        mockContentExists(CONTENT_ID);
        mockUserExists(WATCHER_ID);
        mockUpsert(CONTENT_ID, FIRST_CREATED_AT, true);
        watchingSessionService.start(WATCHER_ID, CONTENT_ID, SESSION_ID, SUBSCRIPTION_ID);

        watchingSessionService.heartbeat(WATCHER_ID, CONTENT_ID, OTHER_SESSION_ID, SUBSCRIPTION_ID);

        verify(watchingSessionSnapshotWriter, never()).renewExpiresAt(any(), any(), any());
    }

    @Test
    @DisplayName("활성 세션이 없으면 DB를 전혀 건드리지 않는다")
    void heartbeat_skipsDbUpdate_whenNoActiveSession() {
        watchingSessionService.heartbeat(WATCHER_ID, CONTENT_ID, SESSION_ID, SUBSCRIPTION_ID);

        verify(watchingSessionSnapshotWriter, never()).renewExpiresAt(any(), any(), any());
    }

    @Test
    @DisplayName("DB renewExpiresAt이 0건이어도 예외 없이 끝난다 (드리프트 신호일 뿐 실패는 아님)")
    void heartbeat_doesNotThrow_whenDbRenewReturnsZero() {
        mockContentExists(CONTENT_ID);
        mockUserExists(WATCHER_ID);
        mockUpsert(CONTENT_ID, FIRST_CREATED_AT, true);
        watchingSessionService.start(WATCHER_ID, CONTENT_ID, SESSION_ID, SUBSCRIPTION_ID);
        when(watchingSessionSnapshotWriter.renewExpiresAt(any(), any(), any())).thenReturn(0);

        assertThatCode(() ->
            watchingSessionService.heartbeat(WATCHER_ID, CONTENT_ID, SESSION_ID, SUBSCRIPTION_ID))
            .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("DB renewExpiresAt이 0이면 insertIfAbsent로 재생성하고 presence의 snapshotId를 동기화한다")
    void heartbeat_recoversMissingSnapshot_whenRenewReturnsZero() {
        mockContentExists(CONTENT_ID);
        mockUserExists(WATCHER_ID);
        mockUpsert(CONTENT_ID, FIRST_CREATED_AT, true);
        watchingSessionService.start(WATCHER_ID, CONTENT_ID, SESSION_ID, SUBSCRIPTION_ID);
        when(watchingSessionSnapshotWriter.renewExpiresAt(any(), any(), any())).thenReturn(0);

        WatchingSessionSnapshot recovered = createSnapshotFixture(
            UUID.randomUUID(), WATCHER_ID, CONTENT_ID, FIRST_CREATED_AT, FIRST_CREATED_AT,
            FIRST_CREATED_AT.plus(1, ChronoUnit.HOURS));
        when(watchingSessionSnapshotWriter.insertIfAbsent(eq(WATCHER_ID), eq(CONTENT_ID), any()))
            .thenReturn(Optional.of(recovered));
        when(watchingSessionPresenceWriter.updateSnapshotIdIfOwner(
            eq(WATCHER_ID), eq(SESSION_ID), eq(SUBSCRIPTION_ID), eq(recovered.getId()), eq(recovered.getUpdatedAt())))
            .thenReturn(true);

        clearInvocations(watchingSessionSnapshotWriter, watchingSessionPresenceWriter);

        watchingSessionService.heartbeat(WATCHER_ID, CONTENT_ID, SESSION_ID, SUBSCRIPTION_ID);

        verify(watchingSessionSnapshotWriter).insertIfAbsent(eq(WATCHER_ID), eq(CONTENT_ID), any());
        verify(watchingSessionPresenceWriter).updateSnapshotIdIfOwner(
            eq(WATCHER_ID), eq(SESSION_ID), eq(SUBSCRIPTION_ID), eq(recovered.getId()), any());
        verify(watchingSessionSnapshotWriter, never()).deleteById(any(), any(), any());
    }

    @Test
    @DisplayName("복구 직후 소유권이 다른 세대로 넘어가 동기화가 실패하면 방금 삽입한 행만 보상 삭제한다")
    void heartbeat_compensatesOrphanRow_whenSnapshotIdSyncFailsRightAfterInsert() {
        mockContentExists(CONTENT_ID);
        mockUserExists(WATCHER_ID);
        mockUpsert(CONTENT_ID, FIRST_CREATED_AT, true);
        watchingSessionService.start(WATCHER_ID, CONTENT_ID, SESSION_ID, SUBSCRIPTION_ID);
        when(watchingSessionSnapshotWriter.renewExpiresAt(any(), any(), any())).thenReturn(0);

        WatchingSessionSnapshot recovered = createSnapshotFixture(
            UUID.randomUUID(), WATCHER_ID, CONTENT_ID, FIRST_CREATED_AT, FIRST_CREATED_AT,
            FIRST_CREATED_AT.plus(1, ChronoUnit.HOURS));
        when(watchingSessionSnapshotWriter.insertIfAbsent(eq(WATCHER_ID), eq(CONTENT_ID), any()))
            .thenReturn(Optional.of(recovered));
        when(watchingSessionPresenceWriter.updateSnapshotIdIfOwner(
            eq(WATCHER_ID), eq(SESSION_ID), eq(SUBSCRIPTION_ID), eq(recovered.getId()), any()))
            .thenReturn(false);

        watchingSessionService.heartbeat(WATCHER_ID, CONTENT_ID, SESSION_ID, SUBSCRIPTION_ID);

        verify(watchingSessionSnapshotWriter).deleteById(eq(WATCHER_ID), eq(recovered.getId()), eq(recovered.getUpdatedAt()));
    }

    @Test
    @DisplayName("낡은 heartbeat가 그 사이 다른 콘텐츠로 넘어간 새 소유자의 행을 발견하면 아무것도 하지 않는다")
    void heartbeat_backsOff_whenAnotherRowAlreadyExists_dueToRaceWithNewStart() {
        mockContentExists(CONTENT_ID);
        mockUserExists(WATCHER_ID);
        mockUpsert(CONTENT_ID, FIRST_CREATED_AT, true);
        watchingSessionService.start(WATCHER_ID, CONTENT_ID, SESSION_ID, SUBSCRIPTION_ID);
        when(watchingSessionSnapshotWriter.renewExpiresAt(any(), any(), any())).thenReturn(0);
        // insertIfAbsent는 watcherId에 이미 다른 행(예: 새 start()가 만든 다른 콘텐츠 행)이
        // 있음을 확인하고 빈 Optional을 반환 - 재현의 핵심
        when(watchingSessionSnapshotWriter.insertIfAbsent(eq(WATCHER_ID), eq(CONTENT_ID), any()))
            .thenReturn(Optional.empty());

        watchingSessionService.heartbeat(WATCHER_ID, CONTENT_ID, SESSION_ID, SUBSCRIPTION_ID);

        verify(watchingSessionPresenceWriter, never())
            .updateSnapshotIdIfOwner(any(), any(), any(), any(), any());
        verify(watchingSessionSnapshotWriter, never()).deleteById(any(), any(), any());
    }

    @Test
    @DisplayName("INSERT가 재시도까지 모두 중복키 충돌로 실패하면 catch로 새지 않고 heartbeat는 예외 없이 끝난다")
    void heartbeat_doesNotThrow_whenInitialInsertAndRetryBothFail() {
        mockContentExists(CONTENT_ID);
        mockUserExists(WATCHER_ID);
        mockUpsert(CONTENT_ID, FIRST_CREATED_AT, true);
        watchingSessionService.start(WATCHER_ID, CONTENT_ID, SESSION_ID, SUBSCRIPTION_ID);
        when(watchingSessionSnapshotWriter.renewExpiresAt(any(), any(), any())).thenReturn(0);
        when(watchingSessionSnapshotWriter.insertIfAbsent(eq(WATCHER_ID), eq(CONTENT_ID), any()))
            .thenThrow(duplicateKeyViolation())
            .thenThrow(duplicateKeyViolation());

        assertThatCode(() ->
            watchingSessionService.heartbeat(WATCHER_ID, CONTENT_ID, SESSION_ID, SUBSCRIPTION_ID))
            .doesNotThrowAnyException();

        verify(watchingSessionSnapshotWriter, times(2)).insertIfAbsent(eq(WATCHER_ID), eq(CONTENT_ID), any());
    }

    @Test
    @DisplayName("첫 시도에서 중복키가 아닌 RuntimeException이 나면 재시도 없이 즉시 격리된다")
    void heartbeat_doesNotThrow_whenFirstAttemptFailsWithNonDuplicateKeyException() {
        mockContentExists(CONTENT_ID);
        mockUserExists(WATCHER_ID);
        mockUpsert(CONTENT_ID, FIRST_CREATED_AT, true);
        watchingSessionService.start(WATCHER_ID, CONTENT_ID, SESSION_ID, SUBSCRIPTION_ID);
        when(watchingSessionSnapshotWriter.renewExpiresAt(any(), any(), any())).thenReturn(0);
        when(watchingSessionSnapshotWriter.insertIfAbsent(eq(WATCHER_ID), eq(CONTENT_ID), any()))
            .thenThrow(new RuntimeException("DB 연결 끊김"));

        assertThatCode(() ->
            watchingSessionService.heartbeat(WATCHER_ID, CONTENT_ID, SESSION_ID, SUBSCRIPTION_ID))
            .doesNotThrowAnyException();

        verify(watchingSessionSnapshotWriter, times(1)).insertIfAbsent(eq(WATCHER_ID), eq(CONTENT_ID), any());
    }

    @Test
    @DisplayName("중복키 충돌이 1회만 발생하면 재시도로 성공한다")
    void heartbeat_recovers_whenDuplicateKeyConflictsOnceThenSucceeds() {
        mockContentExists(CONTENT_ID);
        mockUserExists(WATCHER_ID);
        mockUpsert(CONTENT_ID, FIRST_CREATED_AT, true);
        watchingSessionService.start(WATCHER_ID, CONTENT_ID, SESSION_ID, SUBSCRIPTION_ID);
        when(watchingSessionSnapshotWriter.renewExpiresAt(any(), any(), any())).thenReturn(0);

        WatchingSessionSnapshot recovered = createSnapshotFixture(
            UUID.randomUUID(), WATCHER_ID, CONTENT_ID, FIRST_CREATED_AT, FIRST_CREATED_AT,
            FIRST_CREATED_AT.plus(1, ChronoUnit.HOURS));
        when(watchingSessionSnapshotWriter.insertIfAbsent(eq(WATCHER_ID), eq(CONTENT_ID), any()))
            .thenThrow(duplicateKeyViolation())
            .thenReturn(Optional.of(recovered));
        when(watchingSessionPresenceWriter.updateSnapshotIdIfOwner(
            eq(WATCHER_ID), eq(SESSION_ID), eq(SUBSCRIPTION_ID), eq(recovered.getId()), any()))
            .thenReturn(true);

        watchingSessionService.heartbeat(WATCHER_ID, CONTENT_ID, SESSION_ID, SUBSCRIPTION_ID);

        verify(watchingSessionSnapshotWriter, times(2)).insertIfAbsent(eq(WATCHER_ID), eq(CONTENT_ID), any());
    }

    @Test
    @DisplayName("중복키가 아닌 무결성 위반(FK 위반 등)은 재시도 없이 즉시 전파된다")
    void heartbeat_doesNotThrow_whenNonDuplicateKeyViolationPropagatesWithoutRetry() {
        mockContentExists(CONTENT_ID);
        mockUserExists(WATCHER_ID);
        mockUpsert(CONTENT_ID, FIRST_CREATED_AT, true);
        watchingSessionService.start(WATCHER_ID, CONTENT_ID, SESSION_ID, SUBSCRIPTION_ID);
        when(watchingSessionSnapshotWriter.renewExpiresAt(any(), any(), any())).thenReturn(0);
        when(watchingSessionSnapshotWriter.insertIfAbsent(eq(WATCHER_ID), eq(CONTENT_ID), any()))
            .thenThrow(new DataIntegrityViolationException(
                "FK 위반", new SQLException("FK 위반", "23503")));

        assertThatCode(() ->
            watchingSessionService.heartbeat(WATCHER_ID, CONTENT_ID, SESSION_ID, SUBSCRIPTION_ID))
            .doesNotThrowAnyException();

        verify(watchingSessionSnapshotWriter, times(1)).insertIfAbsent(eq(WATCHER_ID), eq(CONTENT_ID), any());
    }

    @Test
    @DisplayName("복구된 스냅샷의 updatedAt도 마이크로초로 반올림돼 presence에 기록된다")
    void heartbeat_normalizesRecoveredSnapshotUpdatedAt_toMicrosBeforeSyncingPresence() {
        mockContentExists(CONTENT_ID);
        mockUserExists(WATCHER_ID);
        mockUpsert(CONTENT_ID, FIRST_CREATED_AT, true);
        watchingSessionService.start(WATCHER_ID, CONTENT_ID, SESSION_ID, SUBSCRIPTION_ID);
        when(watchingSessionSnapshotWriter.renewExpiresAt(any(), any(), any())).thenReturn(0);

        Instant recoveredUpdatedAtWithNanos = Instant.parse("2026-08-19T00:00:00.987654321Z");
        WatchingSessionSnapshot recovered = createSnapshotFixture(
            UUID.randomUUID(), WATCHER_ID, CONTENT_ID, FIRST_CREATED_AT,
            recoveredUpdatedAtWithNanos, recoveredUpdatedAtWithNanos.plus(1, ChronoUnit.HOURS));
        when(watchingSessionSnapshotWriter.insertIfAbsent(eq(WATCHER_ID), eq(CONTENT_ID), any()))
            .thenReturn(Optional.of(recovered));
        when(watchingSessionPresenceWriter.updateSnapshotIdIfOwner(any(), any(), any(), any(), any()))
            .thenReturn(true);

        watchingSessionService.heartbeat(WATCHER_ID, CONTENT_ID, SESSION_ID, SUBSCRIPTION_ID);

        ArgumentCaptor<Instant> tokenCaptor = ArgumentCaptor.forClass(Instant.class);
        verify(watchingSessionPresenceWriter).updateSnapshotIdIfOwner(
            eq(WATCHER_ID), eq(SESSION_ID), eq(SUBSCRIPTION_ID), eq(recovered.getId()), tokenCaptor.capture());

        assertThat(tokenCaptor.getValue()).isEqualTo(Instant.parse("2026-08-19T00:00:00.987654Z"));
    }
}
