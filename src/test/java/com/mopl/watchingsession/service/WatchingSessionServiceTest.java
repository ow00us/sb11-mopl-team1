package com.mopl.watchingsession.service;

import static java.util.Collections.synchronizedList;
import static java.util.concurrent.Executors.newFixedThreadPool;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
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
import com.mopl.watchingsession.presence.WatchingSessionPresenceWriter;
import com.mopl.watchingsession.repository.WatchingSessionSnapshotRepository;
import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;

@MockitoSettings(strictness = Strictness.LENIENT)
@ExtendWith(MockitoExtension.class)
public class WatchingSessionServiceTest {

    private static final String SUBSCRIPTION_ID = "sub-1";
    private static final String OTHER_SUBSCRIPTION_ID = "sub-2";

    @Mock
    WatchingSessionSnapshotRepository watchingSessionSnapshotRepository;

    @Mock
    ContentRepository contentRepository;

    @Mock
    WatchingSessionSnapshotWriter watchingSessionSnapshotWriter;

    @Mock
    WatchingSessionPresenceWriter watchingSessionPresenceWriter;

    @Mock
    WatchingSessionProperties watchingSessionProperties;

    @Mock
    UserRepository userRepository;

    @InjectMocks
    WatchingSessionService watchingSessionService;

    private static final UUID SNAPSHOT_ID = UUID.fromString("00000000-0000-0000-0000-000000000000");
    private static final UUID WATCHER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID CONTENT_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID NEW_CONTENT_ID = UUID.fromString("33322222-2222-2222-2222-222222222222");
    private static final Instant FIRST_CREATED_AT = Instant.parse("2026-07-29T10:00:00Z");
    private static final String SESSION_ID = "session-123";
    private static final String OTHER_SESSION_ID = "session-999";

    // Content 도메인 전용 헬퍼
    private void mockContentExists(UUID contentId) {
        Content mockContent = mock(Content.class);
        when(mockContent.getId()).thenReturn(contentId);
        when(mockContent.getType()).thenReturn(ContentType.MOVIE);
        when(mockContent.getAverageRating()).thenReturn(BigDecimal.ZERO);
        when(mockContent.getReviewCount()).thenReturn(0L);

        when(contentRepository.existsById(contentId)).thenReturn(true);
        when(contentRepository.findById(contentId)).thenReturn(Optional.of(mockContent));
    }

    // User 도메인 전용 헬퍼
    private void mockUserExists(UUID userId) {
        User mockUser = mock(User.class);
        when(mockUser.getId()).thenReturn(userId);

        when(userRepository.findById(userId)).thenReturn(Optional.of(mockUser));
        when(userRepository.findAllById(any())).thenReturn(List.of(mockUser));
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

    @SuppressWarnings("unchecked")
    private int watcherLockMapSize() throws Exception {
        Field field = WatchingSessionService.class.getDeclaredField("watcherLocks");
        field.setAccessible(true);
        Map<UUID, ?> locks = (Map<UUID, ?>) field.get(watchingSessionService);
        return locks.size();
    }

    /* --- start() 메서드 검증 --- */
    @Test
    @DisplayName("활성 세션 없으면 새 세션 생성")
    void start_success_whenNoActiveSession() {
        mockContentExists(CONTENT_ID);
        mockUserExists(WATCHER_ID);

        WatchingSessionSnapshot created = createSnapshotFixture(
            CONTENT_ID, FIRST_CREATED_AT, FIRST_CREATED_AT, FIRST_CREATED_AT.plus(1, ChronoUnit.HOURS)
        );
        when(watchingSessionSnapshotWriter.upsert(eq(WATCHER_ID), eq(CONTENT_ID), any())).thenReturn(created);

        WatchingSessionService.ReplacedSession replaced =
            watchingSessionService.start(WATCHER_ID, CONTENT_ID, SESSION_ID, SUBSCRIPTION_ID);

        assertThat(replaced.session().id()).isEqualTo(SNAPSHOT_ID);
        assertThat(replaced.session().createdAt()).isEqualTo(FIRST_CREATED_AT);
        assertThat(replaced.session().watcher().userId()).isEqualTo(WATCHER_ID);
        assertThat(replaced.session().content().id()).isEqualTo(CONTENT_ID);
        assertThat(replaced.previous()).isNull();
    }

    @Test
    @DisplayName("start() 성공 시 presence writer에 소유권 정보와 설정된 TTL을 기록")
    void start_success_writesPresence() {
        mockContentExists(CONTENT_ID);
        mockUserExists(WATCHER_ID);
        when(watchingSessionSnapshotWriter.upsert(eq(WATCHER_ID), eq(CONTENT_ID), any()))
            .thenReturn(createSnapshotFixture(CONTENT_ID, FIRST_CREATED_AT, FIRST_CREATED_AT, FIRST_CREATED_AT.plus(1, ChronoUnit.HOURS)));
        Duration presenceTtl = Duration.ofSeconds(60);
        when(watchingSessionProperties.getPresenceTtl()).thenReturn(presenceTtl);

        watchingSessionService.start(WATCHER_ID, CONTENT_ID, SESSION_ID, SUBSCRIPTION_ID);

        verify(watchingSessionPresenceWriter).write(
            eq(WATCHER_ID), eq(CONTENT_ID), eq(SESSION_ID), eq(SUBSCRIPTION_ID), any(), eq(presenceTtl));
    }

    @Test
    @DisplayName("동시 삽입 경합 시 한 번 재시도해 갱신 결과 반환")
    void start_success_retriesOnConcurrentInsertConflict() {
        mockContentExists(CONTENT_ID);
        mockUserExists(WATCHER_ID);
        Instant now = Instant.now();

        WatchingSessionSnapshot afterRetry = createSnapshotFixture(
            CONTENT_ID, FIRST_CREATED_AT, now, now.plus(1, ChronoUnit.HOURS)
        );

        when(watchingSessionSnapshotWriter.upsert(eq(WATCHER_ID), eq(CONTENT_ID), any()))
            .thenThrow(new DataIntegrityViolationException("unique violation"))
            .thenReturn(afterRetry);

        WatchingSessionService.ReplacedSession replaced =
            watchingSessionService.start(WATCHER_ID, CONTENT_ID, SESSION_ID, SUBSCRIPTION_ID);

        assertThat(replaced.session().id()).isEqualTo(SNAPSHOT_ID);
        assertThat(replaced.session().createdAt()).isEqualTo(FIRST_CREATED_AT);
        assertThat(replaced.session().content().id()).isEqualTo(CONTENT_ID);
        verify(watchingSessionSnapshotWriter, times(2)).upsert(eq(WATCHER_ID), eq(CONTENT_ID), any());
    }

    @Test
    @DisplayName("start()는 writer가 반환한 스냅샷을 enrich해서 dto로 변환함")
    void start_success_returnsEnrichedDtoFromWriterResult() {
        mockContentExists(CONTENT_ID);
        mockContentExists(NEW_CONTENT_ID);
        mockUserExists(WATCHER_ID);

        Instant now = Instant.now();
        WatchingSessionSnapshot upserted = createSnapshotFixture(
            NEW_CONTENT_ID, FIRST_CREATED_AT, now, now.plus(1, ChronoUnit.HOURS)
        );

        when(watchingSessionSnapshotWriter.upsert(eq(WATCHER_ID), eq(CONTENT_ID), any()))
            .thenReturn(upserted);

        WatchingSessionService.ReplacedSession replaced =
            watchingSessionService.start(WATCHER_ID, CONTENT_ID, SESSION_ID, SUBSCRIPTION_ID);

        assertThat(replaced.session().id()).isEqualTo(SNAPSHOT_ID);
        assertThat(replaced.session().createdAt()).isEqualTo(FIRST_CREATED_AT);
        assertThat(replaced.session().content().id()).isEqualTo(NEW_CONTENT_ID);
        assertThat(replaced.session().watcher().userId()).isEqualTo(WATCHER_ID);
        verify(watchingSessionSnapshotWriter).upsert(eq(WATCHER_ID), eq(CONTENT_ID), any());
    }

    @Test
    @DisplayName("존재하지 않는 콘텐츠로 시작하면 CONTENT_NOT_FOUND 예외 발생")
    void start_fail_whenContentNotFound() {
        when(contentRepository.existsById(CONTENT_ID)).thenReturn(false);

        assertThatThrownBy(() -> watchingSessionService.start(WATCHER_ID, CONTENT_ID, SESSION_ID, SUBSCRIPTION_ID))
            .isInstanceOf(BusinessException.class)
            .extracting("errorCode")
            .isEqualTo(ErrorCode.CONTENT_NOT_FOUND);

        verify(watchingSessionSnapshotWriter, never()).upsert(any(), any(), any());
    }

    @Test
    @DisplayName("S2의 start가 존재하지 않는 콘텐츠로 실패하면, 기존 S1의 소유권이 보존되어야 함")
    void start_failsValidation_doesNotChangeOwnership() {
        mockContentExists(CONTENT_ID);
        mockUserExists(WATCHER_ID);
        String S1 = "session-1";
        String S2 = "session-2";

        when(watchingSessionSnapshotWriter.upsert(any(), any(), any()))
            .thenReturn(createSnapshotFixture(CONTENT_ID, Instant.now(), Instant.now(), Instant.now()));

        watchingSessionService.start(WATCHER_ID, CONTENT_ID, S1, SUBSCRIPTION_ID);

        UUID invalidContentId = UUID.randomUUID();
        when(contentRepository.existsById(invalidContentId)).thenReturn(false);

        assertThatThrownBy(() -> watchingSessionService.start(WATCHER_ID, invalidContentId, S2, OTHER_SUBSCRIPTION_ID))
            .isInstanceOf(BusinessException.class);

        boolean s2Ended = watchingSessionService.end(WATCHER_ID, S2, OTHER_SUBSCRIPTION_ID);
        assertThat(s2Ended).isFalse();
        verify(watchingSessionSnapshotWriter, never()).delete(WATCHER_ID);

        boolean s1Ended = watchingSessionService.end(WATCHER_ID, S1, SUBSCRIPTION_ID);
        assertThat(s1Ended).isTrue();
        verify(watchingSessionSnapshotWriter).delete(WATCHER_ID);
    }

    @Test
    @DisplayName("start 중 enrich 단계에서 예외가 발생하면 보상 삭제(delete)가 수행되어야 한다")
    void start_throwsExceptionDuringEnrich_thenCompensationDeleteIsCalled() {
        when(contentRepository.existsById(CONTENT_ID)).thenReturn(true);

        WatchingSessionSnapshot dummySnapshot = createSnapshotFixture(
            CONTENT_ID, FIRST_CREATED_AT, FIRST_CREATED_AT, FIRST_CREATED_AT.plus(1, ChronoUnit.HOURS)
        );
        when(watchingSessionSnapshotWriter.upsert(eq(WATCHER_ID), eq(CONTENT_ID), any()))
            .thenReturn(dummySnapshot);

        when(userRepository.findById(WATCHER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> watchingSessionService.start(WATCHER_ID, CONTENT_ID, SESSION_ID, SUBSCRIPTION_ID))
            .isInstanceOf(WatchingSessionService.StartFailedException.class)
                .hasCauseInstanceOf(BusinessException.class);

        verify(watchingSessionSnapshotWriter, times(1)).delete(WATCHER_ID);
    }

    @Test
    @DisplayName("재구독 중 enrich가 실패해 보상 삭제되면, 예외에 직전 세션(이전 콘텐츠)이 실려 나온다")
    void start_throwsExceptionDuringEnrich_carriesEndedPreviousSession() {
        mockContentExists(CONTENT_ID);
        mockContentExists(NEW_CONTENT_ID);
        mockUserExists(WATCHER_ID);
        Instant notExpired = Instant.now().plus(1, ChronoUnit.HOURS);

        // 콘텐츠 A로 정상 시작해 소유권을 확보한다
        when(watchingSessionSnapshotWriter.upsert(eq(WATCHER_ID), eq(CONTENT_ID), any()))
            .thenReturn(createSnapshotFixture(CONTENT_ID, FIRST_CREATED_AT, FIRST_CREATED_AT, notExpired));
        watchingSessionService.start(WATCHER_ID, CONTENT_ID, SESSION_ID, SUBSCRIPTION_ID);

        when(watchingSessionSnapshotRepository.findByWatcherId(WATCHER_ID))
            .thenReturn(Optional.of(createSnapshotFixture(CONTENT_ID, FIRST_CREATED_AT, FIRST_CREATED_AT, notExpired)));

        // 콘텐츠 B로 재구독: upsert는 성공하지만 enrich 단계에서 실패하도록 유도
        when(watchingSessionSnapshotWriter.upsert(eq(WATCHER_ID), eq(NEW_CONTENT_ID), any()))
            .thenReturn(createSnapshotFixture(NEW_CONTENT_ID, FIRST_CREATED_AT, Instant.now(), notExpired));
        when(contentRepository.findById(NEW_CONTENT_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> watchingSessionService.start(WATCHER_ID, NEW_CONTENT_ID, SESSION_ID, OTHER_SUBSCRIPTION_ID))
            .isInstanceOf(WatchingSessionService.StartFailedException.class)
            .extracting(e -> ((WatchingSessionService.StartFailedException) e).getEndedPrevious())
            .extracting(previous -> ((WatchingSessionDto) previous).content().id())
            .isEqualTo(CONTENT_ID);

        verify(watchingSessionSnapshotWriter).delete(WATCHER_ID);
    }

    @Test
    @DisplayName("이전 세션이 없던 첫 구독이 enrich에서 실패하면, 알릴 퇴장이 없으므로 endedPrevious는 null이다")
    void start_throwsExceptionDuringEnrich_hasNoEndedPrevious_whenNoPreviousSession() {
        when(contentRepository.existsById(CONTENT_ID)).thenReturn(true);
        when(watchingSessionSnapshotWriter.upsert(eq(WATCHER_ID), eq(CONTENT_ID), any()))
            .thenReturn(createSnapshotFixture(CONTENT_ID, FIRST_CREATED_AT, FIRST_CREATED_AT, FIRST_CREATED_AT.plus(1, ChronoUnit.HOURS)));
        when(userRepository.findById(WATCHER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> watchingSessionService.start(WATCHER_ID, CONTENT_ID, SESSION_ID, SUBSCRIPTION_ID))
            .isInstanceOf(WatchingSessionService.StartFailedException.class)
            .extracting(e -> ((WatchingSessionService.StartFailedException) e).getEndedPrevious())
            .isNull();
    }

    @Test
    @DisplayName("이미 다른 콘텐츠를 보고 있던 상태에서 start()를 호출하면, 갈아치우기 직전의 이전 세션이 함께 반환")
    void start_success_returnsPreviousSessionWhenReplacingExistingOne() {
        mockContentExists(CONTENT_ID);
        mockContentExists(NEW_CONTENT_ID);
        mockUserExists(WATCHER_ID);
        Instant notExpired = Instant.now().plus(1, ChronoUnit.HOURS);

        when(watchingSessionSnapshotWriter.upsert(eq(WATCHER_ID), eq(CONTENT_ID), any()))
            .thenReturn(createSnapshotFixture(CONTENT_ID, FIRST_CREATED_AT, FIRST_CREATED_AT, notExpired));
        watchingSessionService.start(WATCHER_ID, CONTENT_ID, SESSION_ID, SUBSCRIPTION_ID);

        when(watchingSessionSnapshotRepository.findByWatcherId(WATCHER_ID))
            .thenReturn(Optional.of(createSnapshotFixture(CONTENT_ID, FIRST_CREATED_AT, FIRST_CREATED_AT, notExpired)));

        Instant now = Instant.now();
        when(watchingSessionSnapshotWriter.upsert(eq(WATCHER_ID), eq(NEW_CONTENT_ID), any()))
            .thenReturn(createSnapshotFixture(NEW_CONTENT_ID, FIRST_CREATED_AT, now, now.plus(1, ChronoUnit.HOURS)));

        WatchingSessionService.ReplacedSession replaced =
            watchingSessionService.start(WATCHER_ID, NEW_CONTENT_ID, SESSION_ID, OTHER_SUBSCRIPTION_ID);

        assertThat(replaced.previous()).isNotNull();
        assertThat(replaced.previous().content().id()).isEqualTo(CONTENT_ID);
        assertThat(replaced.session().content().id()).isEqualTo(NEW_CONTENT_ID);
    }

    @Test
    @DisplayName("start() 검증 단계에서 예외가 발생해도 watcherLocks 엔트리 해제")
    void start_failure_stillReleasesWatcherLock_whenValidationFails() throws Exception {
        when(contentRepository.existsById(CONTENT_ID)).thenReturn(false);

        assertThatThrownBy(() -> watchingSessionService.start(WATCHER_ID, CONTENT_ID, SESSION_ID, SUBSCRIPTION_ID))
            .isInstanceOf(BusinessException.class);

        assertThat(watcherLockMapSize()).isZero();
    }

    /* --- end() 메서드 검증 --- */
    @Test
    @DisplayName("종료 시 소유권(sessionId, subscriptionId)이 일치하면 삭제를 수행하고 true를 반환")
    void end_success_returnsTrueAndDeletes_whenOwnershipMatches() {
        mockContentExists(CONTENT_ID);
        mockUserExists(WATCHER_ID);
        when(watchingSessionSnapshotWriter.upsert(any(), any(), any()))
            .thenReturn(createSnapshotFixture(CONTENT_ID, Instant.now(), Instant.now(), Instant.now()));

        watchingSessionService.start(WATCHER_ID, CONTENT_ID, SESSION_ID, SUBSCRIPTION_ID);

        boolean actuallyDeleted = watchingSessionService.end(WATCHER_ID, SESSION_ID, SUBSCRIPTION_ID);

        assertThat(actuallyDeleted).isTrue();
        verify(watchingSessionSnapshotWriter).delete(WATCHER_ID);
    }

    @Test
    @DisplayName("end()가 실제로 삭제(소유권 일치)했을 때만 presence writer에서도 삭제")
    void end_success_deletesPresence_onlyWhenOwnershipMatches() {
        mockContentExists(CONTENT_ID);
        mockUserExists(WATCHER_ID);
        when(watchingSessionSnapshotWriter.upsert(any(), any(), any()))
            .thenReturn(createSnapshotFixture(CONTENT_ID, Instant.now(), Instant.now(), Instant.now()));
        watchingSessionService.start(WATCHER_ID, CONTENT_ID, SESSION_ID, SUBSCRIPTION_ID);

        boolean actuallyDeleted = watchingSessionService.end(WATCHER_ID, SESSION_ID, SUBSCRIPTION_ID);

        assertThat(actuallyDeleted).isTrue();
        verify(watchingSessionPresenceWriter).delete(WATCHER_ID);
    }

    @Test
    @DisplayName("end()가 소유권 불일치로 삭제하지 않으면 presence writer도 호출하지 않음")
    void end_skipsPresenceDelete_whenOwnershipMismatches() {
        mockContentExists(CONTENT_ID);
        mockUserExists(WATCHER_ID);
        when(watchingSessionSnapshotWriter.upsert(any(), any(), any()))
            .thenReturn(createSnapshotFixture(CONTENT_ID, Instant.now(), Instant.now(), Instant.now()));
        watchingSessionService.start(WATCHER_ID, CONTENT_ID, SESSION_ID, SUBSCRIPTION_ID);

        boolean actuallyDeleted = watchingSessionService.end(WATCHER_ID, OTHER_SESSION_ID, SUBSCRIPTION_ID);

        assertThat(actuallyDeleted).isFalse();
        verify(watchingSessionPresenceWriter, never()).delete(any());
    }

    @Test
    @DisplayName("종료 시 소유권이 다르면(다른 탭으로 이동) 삭제를 수행하지 않고 false를 반환")
    void end_success_returnsFalseAndSkipsDelete_whenSessionIdMismatches() {
        mockContentExists(CONTENT_ID);
        mockUserExists(WATCHER_ID);
        when(watchingSessionSnapshotWriter.upsert(any(), any(), any()))
            .thenReturn(createSnapshotFixture(CONTENT_ID, Instant.now(), Instant.now(), Instant.now()));
        watchingSessionService.start(WATCHER_ID, CONTENT_ID, SESSION_ID, SUBSCRIPTION_ID);

        boolean actuallyDeleted = watchingSessionService.end(WATCHER_ID, OTHER_SESSION_ID, SUBSCRIPTION_ID);

        assertThat(actuallyDeleted).isFalse();
        verify(watchingSessionSnapshotWriter, never()).delete(WATCHER_ID);
    }

    @Test
    @DisplayName("종료 시 활성 세션(메모리 소유권)이 없으면 false를 반환하고 예외 없이 종료")
    void end_success_returnsFalse_whenNoActiveSession() {
        boolean actuallyDeleted = watchingSessionService.end(WATCHER_ID, SESSION_ID, SUBSCRIPTION_ID);
        assertThat(actuallyDeleted).isFalse();
        verify(watchingSessionSnapshotWriter, never()).delete(any());
    }

    @Test
    @DisplayName("같은 연결(sessionId)에서 재구독(subscriptionId만 변경)하면, 낡은 subscriptionId로의 end 요청은 false를 반환하고 삭제하지 않는다")
    void end_success_returnsFalse_whenSameSessionButDifferentSubscriptionId() {
        mockContentExists(CONTENT_ID);
        mockUserExists(WATCHER_ID);
        when(watchingSessionSnapshotWriter.upsert(any(), any(), any()))
            .thenReturn(createSnapshotFixture(CONTENT_ID, Instant.now(), Instant.now(), Instant.now()));
        watchingSessionService.start(WATCHER_ID, CONTENT_ID, SESSION_ID, SUBSCRIPTION_ID);

        watchingSessionService.start(WATCHER_ID, CONTENT_ID, SESSION_ID, OTHER_SUBSCRIPTION_ID);

        boolean staleEnded = watchingSessionService.end(WATCHER_ID, SESSION_ID, SUBSCRIPTION_ID);
        assertThat(staleEnded).isFalse();
        verify(watchingSessionSnapshotWriter, never()).delete(WATCHER_ID);

        boolean currentEnded = watchingSessionService.end(WATCHER_ID, SESSION_ID, OTHER_SUBSCRIPTION_ID);
        assertThat(currentEnded).isTrue();
        verify(watchingSessionSnapshotWriter).delete(WATCHER_ID);
    }

    @Test
    @DisplayName("활성 세션이 없는 watcherId로 end()를 호출해도 watcherLocks에 엔트리가 남지 않음")
    void end_noActiveSession_stillReleasesWatcherLock() throws Exception {
        watchingSessionService.end(WATCHER_ID, SESSION_ID, SUBSCRIPTION_ID);

        assertThat(watcherLockMapSize()).isZero();
    }

    @Test
    @DisplayName("정상 종료(start→end) 후 watcherLocks 맵에 해당 watcherId의 락 엔트리가 남지 않음")
    void end_success_removesWatcherLockEntry_afterNormalCompletion() throws Exception {
        mockContentExists(CONTENT_ID);
        mockUserExists(WATCHER_ID);
        when(watchingSessionSnapshotWriter.upsert(any(), any(), any()))
            .thenReturn(createSnapshotFixture(CONTENT_ID, Instant.now(), Instant.now(), Instant.now()));

        watchingSessionService.start(WATCHER_ID, CONTENT_ID, SESSION_ID, SUBSCRIPTION_ID);
        watchingSessionService.end(WATCHER_ID, SESSION_ID, SUBSCRIPTION_ID);

        assertThat(watcherLockMapSize()).isZero();
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

    @Test
    @DisplayName("동시성: 같은 연결에서 낡은 구독(sub-1) 종료와 새 구독(sub-2) 시작이 경합해도 실행이 섞이지 않는다")
    void concurrentEndAndStart_doesNotInterleave() throws Exception {
        // given
        mockContentExists(CONTENT_ID);
        mockContentExists(NEW_CONTENT_ID);
        mockUserExists(WATCHER_ID);

        when(watchingSessionSnapshotWriter.upsert(eq(WATCHER_ID), eq(CONTENT_ID), any()))
            .thenReturn(createSnapshotFixture(CONTENT_ID, Instant.now(), Instant.now(), Instant.now()));
        watchingSessionService.start(WATCHER_ID, CONTENT_ID, SESSION_ID, SUBSCRIPTION_ID);

        // 실행 순서 추적을 위한 스레드 안전 리스트
        List<String> executionOrder = synchronizedList(new ArrayList<>());

        // sub-1의 delete 처리에 의도적 지연(100ms) 추가
        doAnswer(invocation -> {
            executionOrder.add("DELETE_START");
            Thread.sleep(100);
            executionOrder.add("DELETE_END");
            return null;
        }).when(watchingSessionSnapshotWriter).delete(WATCHER_ID);

        // sub-2의 upsert 처리
        doAnswer(invocation -> {
            executionOrder.add("UPSERT_START");
            Thread.sleep(50);
            executionOrder.add("UPSERT_END");
            return createSnapshotFixture(NEW_CONTENT_ID, Instant.now(), Instant.now(), Instant.now());
        }).when(watchingSessionSnapshotWriter).upsert(eq(WATCHER_ID), eq(NEW_CONTENT_ID), any());

        ExecutorService executor = newFixedThreadPool(2);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(2);

        // when
        try {
            // Thread 1: 낡은 구독(sub-1)의 UNSUBSCRIBE에 해당하는 end() 호출
            executor.submit(() -> {
                try {
                    startLatch.await();
                    watchingSessionService.end(WATCHER_ID, SESSION_ID, SUBSCRIPTION_ID);
                } catch (Exception ignored) {
                } finally {
                    doneLatch.countDown();
                }
            });

            // Thread 2: 같은 연결에서 재구독(sub-2)에 해당하는 start() 호출
            executor.submit(() -> {
                try {
                    startLatch.await();
                    watchingSessionService.start(WATCHER_ID, NEW_CONTENT_ID, SESSION_ID, OTHER_SUBSCRIPTION_ID);
                } catch (Exception ignored) {
                } finally {
                    doneLatch.countDown();
                }
            });

            startLatch.countDown(); // 두 스레드 동시 실행 시작

            // 무한 대기(Deadlock) 방지를 위해 Timeout(3초) 설정 및 검증
            boolean completed = doneLatch.await(3, TimeUnit.SECONDS);
            assertThat(completed)
                .as("교착 상태(Deadlock) 발생: 제한 시간(3초) 내에 스레드 작업이 완료되지 않았습니다.")
                .isTrue();
        } finally {
            executor.shutdown();
        }

        // then
        // 락(Lock)에 의해 둘 중 하나가 완전히 끝난 후 다음 작업이 실행되어야 함
        // (DELETE와 UPSERT 중간에 다른 로직이 끼어들지 않음)
        String joinedOrder = String.join(",", executionOrder);

        // 시나리오 1: Thread 1(end)이 락을 먼저 획득
        // sub-1이 정상 삭제된 후, sub-2가 새로 생성됨
        boolean atomicOrder1 = joinedOrder.equals("DELETE_START,DELETE_END,UPSERT_START,UPSERT_END");

        // 시나리오 2: Thread 2(start)가 락을 먼저 획득
        // sub-2가 소유권을 덮어버림 -> 뒤늦게 락을 얻은 Thread 1(sub-1 end)은
        // 소유권 불일치(subscriptionId 다름)로 삭제를 스킵함!
        boolean atomicOrder2 = joinedOrder.equals("UPSERT_START,UPSERT_END");

        assertThat(atomicOrder1 || atomicOrder2)
            .as("작업이 원자적으로 실행되지 않고 중간에 섞임. 실행 로그: " + joinedOrder)
            .isTrue();

        // 두 실행 순서 중 어느 쪽이었든, 최종 소유권은 항상 재구독한 sub-2(NEW_CONTENT_ID)여야 한다.
        boolean finalOwnerIsSub2 = watchingSessionService.end(WATCHER_ID, SESSION_ID, OTHER_SUBSCRIPTION_ID);
        assertThat(finalOwnerIsSub2)
            .as("최종 소유권은 재구독한 subscriptionId(sub-2)여야 한다")
            .isTrue();
    }

    @Test
    @DisplayName("서로 다른 N명의 watcherId로 start→end를 반복해도 watcherLocks 맵이 누적되지 않음")
    void watcherLocks_doesNotAccumulate_acrossManyWatchers() throws Exception {
        int watcherCount = 50;
        for (int i = 0; i < watcherCount; i++) {
            UUID watcherId = UUID.randomUUID();
            mockContentExists(CONTENT_ID);
            mockUserExists(watcherId);
            when(watchingSessionSnapshotWriter.upsert(eq(watcherId), any(), any()))
                .thenReturn(createSnapshotFixture(
                    UUID.randomUUID(), watcherId, CONTENT_ID,
                    Instant.now(), Instant.now(), Instant.now().plus(1, ChronoUnit.HOURS)));

            watchingSessionService.start(watcherId, CONTENT_ID, SESSION_ID, SUBSCRIPTION_ID);
            watchingSessionService.end(watcherId, SESSION_ID, SUBSCRIPTION_ID);
        }

        assertThat(watcherLockMapSize())
            .as("N명의 watcher가 정상 종료했음에도 락 맵 크기가 N으로 단조 증가하면 안 됨")
            .isZero();
    }

    /* --- heartbeat() 메서드 검증 --- */

    @Test
    @DisplayName("소유권이 일치하면 DB expiresAt과 Redis presence TTL을 함께 갱신한다")
    void heartbeat_success_renewsDbAndPresence_whenOwnershipMatches() {
        mockContentExists(CONTENT_ID);
        mockUserExists(WATCHER_ID);
        when(watchingSessionSnapshotWriter.upsert(eq(WATCHER_ID), eq(CONTENT_ID), any()))
            .thenReturn(createSnapshotFixture(CONTENT_ID, FIRST_CREATED_AT, FIRST_CREATED_AT, FIRST_CREATED_AT.plus(1, ChronoUnit.HOURS)));
        watchingSessionService.start(WATCHER_ID, CONTENT_ID, SESSION_ID, SUBSCRIPTION_ID);

        when(watchingSessionProperties.getSessionTtl()).thenReturn(Duration.ofMinutes(30));
        when(watchingSessionProperties.getPresenceTtl()).thenReturn(Duration.ofSeconds(60));
        when(watchingSessionSnapshotWriter.renewExpiresAt(eq(WATCHER_ID), eq(CONTENT_ID), any(), any()))
            .thenReturn(1);

        watchingSessionService.heartbeat(WATCHER_ID, CONTENT_ID, SESSION_ID, SUBSCRIPTION_ID);

        verify(watchingSessionSnapshotWriter).renewExpiresAt(eq(WATCHER_ID), eq(CONTENT_ID), any(), any());
        verify(watchingSessionPresenceWriter).renew(eq(WATCHER_ID), any());
    }

    @Test
    @DisplayName("소유권이 일치하지 않으면(낡은 탭·재구독으로 밀려남) DB·Redis 어느 쪽도 갱신하지 않는다")
    void heartbeat_noop_whenOwnershipMismatches() {
        mockContentExists(CONTENT_ID);
        mockUserExists(WATCHER_ID);
        when(watchingSessionSnapshotWriter.upsert(any(), any(), any()))
            .thenReturn(createSnapshotFixture(CONTENT_ID, Instant.now(), Instant.now(), Instant.now()));
        watchingSessionService.start(WATCHER_ID, CONTENT_ID, SESSION_ID, SUBSCRIPTION_ID);

        // 다른 sessionId로 도착한 heartbeat (다른 탭에서 이미 소유권을 넘겨받은 상황을 흉내)
        watchingSessionService.heartbeat(WATCHER_ID, CONTENT_ID, OTHER_SESSION_ID, SUBSCRIPTION_ID);

        verify(watchingSessionSnapshotWriter, never()).renewExpiresAt(any(), any(), any(), any());
        verify(watchingSessionPresenceWriter, never()).renew(any(), any());
    }

    @Test
    @DisplayName("활성 세션(메모리 소유권)이 없으면 DB·Redis 어느 쪽도 갱신하지 않는다")
    void heartbeat_noop_whenNoActiveSession() {
        watchingSessionService.heartbeat(WATCHER_ID, CONTENT_ID, SESSION_ID, SUBSCRIPTION_ID);

        verify(watchingSessionSnapshotWriter, never()).renewExpiresAt(any(), any(), any(), any());
        verify(watchingSessionPresenceWriter, never()).renew(any(), any());
    }

    @Test
    @DisplayName("DB 갱신이 0건이면(그 사이 end()로 삭제됨) Redis presence는 갱신하지 않는다")
    void heartbeat_skipsPresenceRenew_whenDbRenewReturnsZero() {
        mockContentExists(CONTENT_ID);
        mockUserExists(WATCHER_ID);
        when(watchingSessionSnapshotWriter.upsert(any(), any(), any()))
            .thenReturn(createSnapshotFixture(CONTENT_ID, Instant.now(), Instant.now(), Instant.now()));
        when(watchingSessionProperties.getSessionTtl()).thenReturn(Duration.ofMinutes(30));
        when(watchingSessionProperties.getPresenceTtl()).thenReturn(Duration.ofSeconds(60));
        watchingSessionService.start(WATCHER_ID, CONTENT_ID, SESSION_ID, SUBSCRIPTION_ID);

        when(watchingSessionSnapshotWriter.renewExpiresAt(any(), any(), any(), any())).thenReturn(0);

        watchingSessionService.heartbeat(WATCHER_ID, CONTENT_ID, SESSION_ID, SUBSCRIPTION_ID);

        verify(watchingSessionSnapshotWriter).renewExpiresAt(any(), any(), any(), any());
        verify(watchingSessionPresenceWriter, never()).renew(any(), any());
    }

    @Test
    @DisplayName("DB 갱신 중 예외가 발생해도 호출자에게 전파되지 않고 Redis 갱신도 시도하지 않는다")
    void heartbeat_isolatesDbFailure() {
        mockContentExists(CONTENT_ID);
        mockUserExists(WATCHER_ID);
        when(watchingSessionSnapshotWriter.upsert(any(), any(), any()))
            .thenReturn(createSnapshotFixture(CONTENT_ID, Instant.now(), Instant.now(), Instant.now()));
        when(watchingSessionProperties.getSessionTtl()).thenReturn(Duration.ofMinutes(30));
        when(watchingSessionProperties.getPresenceTtl()).thenReturn(Duration.ofSeconds(60));
        watchingSessionService.start(WATCHER_ID, CONTENT_ID, SESSION_ID, SUBSCRIPTION_ID);

        when(watchingSessionSnapshotWriter.renewExpiresAt(any(), any(), any(), any()))
            .thenThrow(new RuntimeException("DB 연결 끊김"));

        assertThatCode(() ->
            watchingSessionService.heartbeat(WATCHER_ID, CONTENT_ID, SESSION_ID, SUBSCRIPTION_ID))
            .doesNotThrowAnyException();

        verify(watchingSessionPresenceWriter, never()).renew(any(), any());
    }

    @Test
    @DisplayName("heartbeat 처리 후 watcherLocks 맵에 엔트리가 남지 않는다")
    void heartbeat_stillReleasesWatcherLock() throws Exception {
        mockContentExists(CONTENT_ID);
        mockUserExists(WATCHER_ID);
        when(watchingSessionSnapshotWriter.upsert(any(), any(), any()))
            .thenReturn(createSnapshotFixture(CONTENT_ID, Instant.now(), Instant.now(), Instant.now()));
        when(watchingSessionProperties.getSessionTtl()).thenReturn(Duration.ofMinutes(30));
        when(watchingSessionProperties.getPresenceTtl()).thenReturn(Duration.ofSeconds(60));
        watchingSessionService.start(WATCHER_ID, CONTENT_ID, SESSION_ID, SUBSCRIPTION_ID);

        watchingSessionService.heartbeat(WATCHER_ID, CONTENT_ID, SESSION_ID, SUBSCRIPTION_ID);

        assertThat(watcherLockMapSize()).isZero();
    }
}
