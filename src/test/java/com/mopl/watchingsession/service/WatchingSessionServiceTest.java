package com.mopl.watchingsession.service;


import static java.util.Collections.synchronizedList;
import static java.util.concurrent.Executors.newFixedThreadPool;
import static org.assertj.core.api.Assertions.assertThat;
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
import com.mopl.watchingsession.dto.WatchingSessionDto;
import com.mopl.watchingsession.entity.WatchingSessionSnapshot;
import com.mopl.watchingsession.repository.WatchingSessionSnapshotRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
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

    @Mock
    WatchingSessionSnapshotRepository watchingSessionSnapshotRepository;

    @Mock
    ContentRepository contentRepository;

    @Mock
    WatchingSessionSnapshotWriter watchingSessionSnapshotWriter;

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

        // Content 관련 Repository 스텁
        when(contentRepository.existsById(contentId)).thenReturn(true);
        when(contentRepository.findById(contentId)).thenReturn(Optional.of(mockContent));
    }

    // User 도메인 전용 헬퍼
    private void mockUserExists(UUID userId) {
        User mockUser = mock(User.class);
        when(mockUser.getId()).thenReturn(userId);

        // User 관련 Repository 스텁 (단건/다건 모두 처리)
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

    /* --- start() 메서드 검증 --- */
    @Test
    @DisplayName("활성 세션 없으면 새 세션 생성")
    void start_success_whenNoActiveSession() {
        // given
        mockContentExists(CONTENT_ID);
        mockUserExists(WATCHER_ID);

        WatchingSessionSnapshot created = createSnapshotFixture(
            CONTENT_ID, FIRST_CREATED_AT, FIRST_CREATED_AT, FIRST_CREATED_AT.plus(1, ChronoUnit.HOURS)
        );
        when(watchingSessionSnapshotWriter.upsert(eq(WATCHER_ID), eq(CONTENT_ID), any())).thenReturn(created);

        // when
        WatchingSessionDto response = watchingSessionService.start(WATCHER_ID, CONTENT_ID, SESSION_ID);

        // then
        assertThat(response.id()).isEqualTo(SNAPSHOT_ID);
        assertThat(response.createdAt()).isEqualTo(FIRST_CREATED_AT);
        assertThat(response.watcher().userId()).isEqualTo(WATCHER_ID);
        assertThat(response.content().id()).isEqualTo(CONTENT_ID);
    }

    @Test
    @DisplayName("동시 삽입 경합 시 한 번 재시도해 갱신 결과 반환")
    void start_success_retriesOnConcurrentInsertConflict() {
        // given
        mockContentExists(CONTENT_ID);
        mockUserExists(WATCHER_ID);

        Instant now = Instant.now();

        WatchingSessionSnapshot afterRetry = createSnapshotFixture(
            CONTENT_ID, FIRST_CREATED_AT, now, now.plus(1, ChronoUnit.HOURS)
        );

        when(watchingSessionSnapshotWriter.upsert(eq(WATCHER_ID), eq(CONTENT_ID), any()))
            .thenThrow(new DataIntegrityViolationException("unique violation"))
            .thenReturn(afterRetry);

        // when
        WatchingSessionDto response = watchingSessionService.start(WATCHER_ID, CONTENT_ID, SESSION_ID);

        // then
        assertThat(response.id()).isEqualTo(SNAPSHOT_ID);
        // DTO의 createdAt에는 Entity의 createdAt이 아닌 updatedAt(NOW)가 나와야함
        assertThat(response.createdAt()).isEqualTo(now);
        assertThat(response.createdAt()).isNotEqualTo(FIRST_CREATED_AT);
        assertThat(response.content().id()).isEqualTo(CONTENT_ID);
        verify(watchingSessionSnapshotWriter, times(2)).upsert(eq(WATCHER_ID), eq(CONTENT_ID), any());
    }

    @Test
    @DisplayName("start()는 writer가 반환한 스냅샷을 enrich해서 dto로 변환함")
    void start_success_returnsEnrichedDtoFromWriterResult() {
        // given
        mockContentExists(CONTENT_ID);
        mockContentExists(NEW_CONTENT_ID);
        mockUserExists(WATCHER_ID);

        Instant now = Instant.now();

        WatchingSessionSnapshot upserted = createSnapshotFixture(
            NEW_CONTENT_ID, FIRST_CREATED_AT, now, now.plus(1, ChronoUnit.HOURS)
        );

        when(watchingSessionSnapshotWriter.upsert(eq(WATCHER_ID), eq(CONTENT_ID), any()))
            .thenReturn(upserted);

        // when
        WatchingSessionDto response = watchingSessionService.start(WATCHER_ID, CONTENT_ID, SESSION_ID);

        // then - enrich되어 나오는지 확인
        assertThat(response.id()).isEqualTo(SNAPSHOT_ID);
        assertThat(response.createdAt()).isEqualTo(now);
        assertThat(response.createdAt()).isNotEqualTo(FIRST_CREATED_AT);
        assertThat(response.content().id()).isEqualTo(NEW_CONTENT_ID);
        assertThat(response.watcher().userId()).isEqualTo(WATCHER_ID);
        verify(watchingSessionSnapshotWriter).upsert(eq(WATCHER_ID), eq(CONTENT_ID), any());
    }

    @Test
    @DisplayName("존재하지 않는 콘텐츠로 시작하면 CONTENT_NOT_FOUND 예외 발생")
    void start_fail_whenContentNotFound() {
        // given
        when(contentRepository.existsById(CONTENT_ID)).thenReturn(false);

        // when & then
        assertThatThrownBy(() -> watchingSessionService.start(WATCHER_ID, CONTENT_ID, SESSION_ID))
            .isInstanceOf(BusinessException.class)
            .extracting("errorCode")
            .isEqualTo(ErrorCode.CONTENT_NOT_FOUND);

        verify(watchingSessionSnapshotWriter, never()).upsert(any(), any(), any());
    }

    /* --- end() 메서드 검증 --- */
    @Test
    @DisplayName("종료 시 소유권(sessionId)이 일치하면 삭제를 수행하고 true를 반환")
    void end_success_returnsTrueAndDeletes_whenSessionIdMatches() {
        // given: 먼저 start를 호출하여 메모리에 소유권을 세팅함
        mockContentExists(CONTENT_ID);
        mockUserExists(WATCHER_ID);
        when(watchingSessionSnapshotWriter.upsert(any(), any(), any()))
            .thenReturn(createSnapshotFixture(CONTENT_ID, Instant.now(), Instant.now(), Instant.now()));

        watchingSessionService.start(WATCHER_ID, CONTENT_ID, SESSION_ID);

        // when
        boolean actuallyDeleted = watchingSessionService.end(WATCHER_ID, SESSION_ID);

        // then
        assertThat(actuallyDeleted).isTrue();
        verify(watchingSessionSnapshotRepository).deleteByWatcherId(WATCHER_ID);
    }

    @Test
    @DisplayName("종료 시 소유권이 다르면(다른 탭으로 이동) 삭제를 수행하지 않고 false를 반환")
    void end_success_returnsFalseAndSkipsDelete_whenSessionIdMismatches() {
        // given: SESSION_ID로 세션을 시작함
        mockContentExists(CONTENT_ID);
        mockUserExists(WATCHER_ID);
        when(watchingSessionSnapshotWriter.upsert(any(), any(), any()))
            .thenReturn(createSnapshotFixture(CONTENT_ID, Instant.now(), Instant.now(), Instant.now()));
        watchingSessionService.start(WATCHER_ID, CONTENT_ID, SESSION_ID);

        // when: 늦게 도착한 과거 탭(OTHER_SESSION_ID)의 종료 요청
        boolean actuallyDeleted = watchingSessionService.end(WATCHER_ID, OTHER_SESSION_ID);

        // then
        assertThat(actuallyDeleted).isFalse();
        // DB 삭제가 수행되지 않았음을 보장
        verify(watchingSessionSnapshotRepository, never()).deleteByWatcherId(WATCHER_ID);
    }

    @Test
    @DisplayName("종료 시 활성 세션(메모리 소유권)이 없으면 false를 반환하고 예외 없이 종료")
    void end_success_returnsFalse_whenNoActiveSession() {
        // when
        boolean actuallyDeleted = watchingSessionService.end(WATCHER_ID, SESSION_ID);

        // then
        assertThat(actuallyDeleted).isFalse();
        verify(watchingSessionSnapshotRepository, never()).deleteByWatcherId(any());
    }

    /* --- get() 메서드 검증 --- */
    @Test
    @DisplayName("활성 세션이 있으면 조회 결과 반환")
    void get_success_whenActiveSessionExists() {
        // given
        mockContentExists(CONTENT_ID);
        mockUserExists(WATCHER_ID);

        Instant now = Instant.now();

        WatchingSessionSnapshot snapshot = createSnapshotFixture(
            CONTENT_ID, FIRST_CREATED_AT, now, now.plus(1, ChronoUnit.HOURS)
        );
        when(watchingSessionSnapshotRepository.findByWatcherId(WATCHER_ID)).thenReturn(Optional.of(snapshot));

        // when
        Optional<WatchingSessionDto> result = watchingSessionService.get(WATCHER_ID);

        // then
        assertThat(result).isPresent();
        WatchingSessionDto dto = result.get();
        assertThat(dto.id()).isEqualTo(SNAPSHOT_ID);
        assertThat(dto.createdAt()).isEqualTo(now);
        assertThat(dto.createdAt()).isNotEqualTo(FIRST_CREATED_AT);
        assertThat(dto.watcher().userId()).isEqualTo(WATCHER_ID);
        assertThat(dto.content().id()).isEqualTo(CONTENT_ID);
    }

    @Test
    @DisplayName("활성 세션이 없으면 빈 Optional 반환")
    void get_success_whenNoActiveSession() {
        // given
        Instant now = Instant.now();
        WatchingSessionSnapshot expired = createSnapshotFixture(
            CONTENT_ID, FIRST_CREATED_AT, now, now.minusSeconds(1)
        );
        when(watchingSessionSnapshotRepository.findByWatcherId(WATCHER_ID)).thenReturn(Optional.of(expired));

        // when
        Optional<WatchingSessionDto> result = watchingSessionService.get(WATCHER_ID);

        // then
        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("스냅샷은 있으나 유저가 조회되지 않으면 RESOURCE_NOT_FOUND 예외 발생")
    void get_fail_whenWatcherNotFoundDuringEnrich() {
        // given
        Instant now = Instant.now();
        WatchingSessionSnapshot snapshot = createSnapshotFixture(
            CONTENT_ID, FIRST_CREATED_AT, now, now.plus(1, ChronoUnit.HOURS)
        );

        when(watchingSessionSnapshotRepository.findByWatcherId(WATCHER_ID))
            .thenReturn(Optional.of(snapshot));

        // 유저 조회가 안 되는 상황 가정 (탈퇴 등)
        when(userRepository.findById(WATCHER_ID)).thenReturn(Optional.empty());

        // when & then
        assertThatThrownBy(() -> watchingSessionService.get(WATCHER_ID))
            .isInstanceOf(BusinessException.class)
            .extracting("errorCode")
            .isEqualTo(ErrorCode.RESOURCE_NOT_FOUND);
    }

    /* --- getListByContent() 메서드 검증 --- */
    @Test
    @DisplayName("정상 조회 시 CursorResponse로 변환되어 반환")
    void getListByContent_success_returnsData() {
        // given
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

        // when
        CursorResponse<WatchingSessionDto> result = watchingSessionService.getListByContent(
            CONTENT_ID, null, null, null, 10, "createdAt", "DESCENDING"
        );

        // then
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
        // given
        mockContentExists(CONTENT_ID);

        when(watchingSessionSnapshotRepository.findByContentIdFirstPageAsc(
            eq(CONTENT_ID), isNull(), any(Instant.class), any()))
            .thenReturn(List.of());
        when(watchingSessionSnapshotRepository.countByContentId(
            eq(CONTENT_ID), isNull(), any()))
            .thenReturn(0L);

        // when
        watchingSessionService.getListByContent(
            CONTENT_ID, null, null, null, 10, "createdAt", "ASCENDING"
        );

        // then: 오름차순 메서드가 호출되고, 내림차순 메서드는 호출되지 않아야 함
        verify(watchingSessionSnapshotRepository).findByContentIdFirstPageAsc(
            eq(CONTENT_ID), isNull(), any(Instant.class), any());
        verify(watchingSessionSnapshotRepository, never()).findByContentIdFirstPageDesc(
            any(), any(), any(), any());
    }

    @Test
    @DisplayName("sortDirection=ASCENDING이고 cursor가 있으면 findByContentIdAfterAscending을 호출한다")
    void getListByContent_success_ascendingWithCursorCallsCorrectMethod() {
        // given
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

        // when
        watchingSessionService.getListByContent(
            CONTENT_ID, null, cursor, idAfter, 10, "createdAt", "ASCENDING"
        );

        // then
        verify(watchingSessionSnapshotRepository).findByContentIdAfterAsc(
            eq(CONTENT_ID), isNull(), any(Instant.class), eq(now), eq(idAfter), any());
        verify(watchingSessionSnapshotRepository, never()).findByContentIdAfterDesc(
            any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("시청자가 없으면 빈 목록과 totalCount 0 반환")
    void getListByContent_success_returnsEmptyList() {
        // given
        mockContentExists(CONTENT_ID);
        when(watchingSessionSnapshotRepository.findByContentIdFirstPageDesc(
            eq(CONTENT_ID), isNull(), any(), any()))
            .thenReturn(List.of());
        when(watchingSessionSnapshotRepository.countByContentId(
            eq(CONTENT_ID), isNull(), any()))
            .thenReturn(0L);

        // when
        CursorResponse<WatchingSessionDto> result = watchingSessionService.getListByContent(
            CONTENT_ID, null, null, null, 10, "createdAt", "DESCENDING"
        );

        // then
        assertThat(result.data()).isEmpty();
        assertThat(result.totalCount()).isEqualTo(0L);
        assertThat(result.hasNext()).isFalse();
    }

    @Test
    @DisplayName("watcherNameLike로 필터링된 결과만 반환")
    void getListByContent_success_filtersByWatcherName() {
        // given
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

        // when
        CursorResponse<WatchingSessionDto> result = watchingSessionService.getListByContent(
            CONTENT_ID, "김", null, null, 10, "createdAt", "DESCENDING"
        );

        // then
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
        // given
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

        // when
        CursorResponse<WatchingSessionDto> result = watchingSessionService.getListByContent(
            CONTENT_ID, rawInput, null, null, 10, "createdAt", "DESCENDING"
        );

        // then
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
        // given
        mockContentExists(CONTENT_ID);
        mockUserExists(WATCHER_ID);

        Instant now = Instant.now();

        WatchingSessionSnapshot s1 = createSnapshotFixture(
            UUID.randomUUID(), WATCHER_ID, CONTENT_ID, FIRST_CREATED_AT, now, now.plus(1, ChronoUnit.HOURS)
        );

        WatchingSessionSnapshot s2 = createSnapshotFixture(
            UUID.randomUUID(), WATCHER_ID, CONTENT_ID, FIRST_CREATED_AT, now.minusSeconds(1), now.plus(1, ChronoUnit.HOURS)
        );

        // limit=1인데 2건 반환 -> hasNext 판단용 1건 초과 조회
        when(watchingSessionSnapshotRepository.findByContentIdFirstPageDesc(
            eq(CONTENT_ID), isNull(), any(), any()))
            .thenReturn(List.of(s1, s2));
        when(watchingSessionSnapshotRepository.countByContentId(
            eq(CONTENT_ID), isNull(), any()))
            .thenReturn(2L);

        // when
        CursorResponse<WatchingSessionDto> result = watchingSessionService.getListByContent(
            CONTENT_ID, null, null, null, 1, "createdAt", "DESCENDING"
        );

        // then
        assertThat(result.data()).hasSize(1);
        assertThat(result.hasNext()).isTrue();
        assertThat(result.totalCount()).isEqualTo(2L);
        assertThat(result.nextCursor()).isEqualTo(CursorUtils.encodeInstant(s1.getUpdatedAt()));
        assertThat(result.nextIdAfter()).isEqualTo(s1.getId());
        verify(watchingSessionSnapshotRepository).countByContentId(
            eq(CONTENT_ID), isNull(), any());
    }

    @Test
    @DisplayName("cursor/idAfter로 다음 페이지 조회")
    void getListByContent_success_withCursorMovesToNextPage() {
        // given
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

        // when
        CursorResponse<WatchingSessionDto> result = watchingSessionService.getListByContent(
            CONTENT_ID, null, cursor, idAfter, 10, "createdAt", "DESCENDING"
        );

        // then
        assertThat(result.data()).hasSize(1);
        verify(watchingSessionSnapshotRepository).findByContentIdAfterDesc(
            eq(CONTENT_ID), isNull(), any(), eq(now), eq(idAfter), any());
    }

    @Test
    @DisplayName("만료된 세션은 목록에서 제외")
    void getListByContent_success_excludesExpiredViaQuery() {
        // given
        // Repository에서 이미 expiresAt > now 조건으로 걸러주므로 서비스는 now 값을 정확히 넘기는지만 검증
        // given
        mockContentExists(CONTENT_ID);
        when(watchingSessionSnapshotRepository.findByContentIdFirstPageDesc(
            eq(CONTENT_ID), isNull(), any(Instant.class), any()))
            .thenReturn(List.of());
        when(watchingSessionSnapshotRepository.countByContentId(
            eq(CONTENT_ID), isNull(), any()))
            .thenReturn(0L);

        // when
        watchingSessionService.getListByContent(
            CONTENT_ID, null, null, null, 10, "createdAt", "DESCENDING"
        );

        // then
        verify(watchingSessionSnapshotRepository).findByContentIdFirstPageDesc(
            eq(CONTENT_ID), isNull(), any(Instant.class), any());
    }

    @Test
    @DisplayName("정상 조회 시 N+1 없이 배치로 사용자를 조회한다")
    void getListByContent_success_batchFetchesWatchers() {
        // given
        mockContentExists(CONTENT_ID);
        mockUserExists(WATCHER_ID);

        Instant now = Instant.now();

        WatchingSessionSnapshot snapshot = createSnapshotFixture(
            CONTENT_ID, FIRST_CREATED_AT, now, now.plusSeconds(60)
        );
        when(watchingSessionSnapshotRepository.findByContentIdFirstPageDesc(
            eq(CONTENT_ID), any(), any(), any()))
            .thenReturn(List.of(snapshot));

        User mockUser = mock(User.class);
        when(mockUser.getId()).thenReturn(WATCHER_ID);
        when(userRepository.findAllById(List.of(WATCHER_ID))).thenReturn(List.of(mockUser));

        when(watchingSessionSnapshotRepository.countByContentId(eq(CONTENT_ID), any(), any())).thenReturn(1L);

        // when
        CursorResponse<WatchingSessionDto> result = watchingSessionService.getListByContent(
            CONTENT_ID, null, null, null, 10, "createdAt", "DESCENDING");

        // then
        assertThat(result.data()).hasSize(1);
        verify(userRepository).findAllById(List.of(WATCHER_ID));
        verify(userRepository, never()).findById(any());
    }

    @Test
    @DisplayName("존재하지 않는 콘텐츠면 CONTENT_NOT_FOUND 예외 발생")
    void getListByContent_fail_contentNotFound() {
        // given
        when(contentRepository.findById(CONTENT_ID)).thenReturn(Optional.empty());

        // when & then
        assertThatThrownBy(() -> watchingSessionService.getListByContent(
            CONTENT_ID, null, null, null, 10, "createdAt", "DESCENDING"
        ))
            .isInstanceOf(BusinessException.class)
            .extracting("errorCode")
            .isEqualTo(ErrorCode.CONTENT_NOT_FOUND);
    }

    @Test
    @DisplayName("sortBy가 createdAt이 아니면 INVALID_INPUT 에외 발생")
    void getListByContent_fail_invalidSortBy() {
        // given
        mockContentExists(CONTENT_ID);

        // when & then
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
        // given
        mockContentExists(CONTENT_ID);
        Instant now = Instant.now();
        String cursor = CursorUtils.encodeInstant(now);

        // when & then
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
        // given
        mockContentExists(CONTENT_ID);

        // when & then
        assertThatThrownBy(() -> watchingSessionService.getListByContent(
            CONTENT_ID, null, "invalid-cursor!!", UUID.randomUUID(), 10, "createdAt", "DESCENDING"))
            .isInstanceOf(BusinessException.class)
            .extracting("errorCode").isEqualTo(ErrorCode.INVALID_INPUT);
    }

    @Test
    @DisplayName("idAfter만 있고 cursor가 없으면 INVALID_INPUT 예외 발생")
    void getListByContent_fail_idAfterWithoutCursor() {
        // given
        mockContentExists(CONTENT_ID);

        // when & then
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
        // given
        mockContentExists(CONTENT_ID);

        // when & then
        assertThatThrownBy(() -> watchingSessionService.getListByContent(
            CONTENT_ID, null, null, null, 10, "createdAt", "WRONG"
        ))
            .isInstanceOf(BusinessException.class)
            .extracting("errorCode")
            .isEqualTo(ErrorCode.INVALID_INPUT);
    }

    @Test
    @DisplayName("watcherNameLike에 %, _가 포함되면 이스케이프되어 Repository에 전달")
    void getListByContent_escapesLikeWildcards() {
        // given
        mockContentExists(CONTENT_ID);
        when(watchingSessionSnapshotRepository.findByContentIdFirstPageDesc(
            eq(CONTENT_ID), eq("100\\%\\_off"), any(), any()))
            .thenReturn(List.of());
        when(watchingSessionSnapshotRepository.countByContentId(
            eq(CONTENT_ID), eq("100\\%\\_off"), any()))
            .thenReturn(0L);

        // when
        watchingSessionService.getListByContent(
            CONTENT_ID, "100%_off", null, null, 10, "createdAt", "DESCENDING"
        );

        // then
        verify(watchingSessionSnapshotRepository).findByContentIdFirstPageDesc(
            eq(CONTENT_ID), eq("100\\%\\_off"), any(), any());
    }

    @Test
    @DisplayName("요청 sortDirection이 소문자여도 응답은 대문자로 정규화된다")
    void getListByContent_success_normalizesSortDirectionCase() {
        // given
        mockContentExists(CONTENT_ID);
        when(watchingSessionSnapshotRepository.findByContentIdFirstPageAsc(
            eq(CONTENT_ID), any(), any(), any()))
            .thenReturn(List.of());
        when(watchingSessionSnapshotRepository.countByContentId(eq(CONTENT_ID), any(), any())).thenReturn(0L);

        // when
        CursorResponse<WatchingSessionDto> result = watchingSessionService.getListByContent(
            CONTENT_ID, null, null, null, 10, "createdAt", "ascending");

        // then
        assertThat(result.sortDirection()).isEqualTo("ASCENDING");
    }

    @Test
    @DisplayName("S2의 start가 존재하지 않는 콘텐츠로 실패하면, 기존 S1의 소유권이 보존되어야 함")
    void start_failsValidation_doesNotChangeOwnership() {
        // given
        mockContentExists(CONTENT_ID);
        mockUserExists(WATCHER_ID);
        String S1 = "session-1";
        String S2 = "session-2";

        when(watchingSessionSnapshotWriter.upsert(any(), any(), any()))
            .thenReturn(createSnapshotFixture(CONTENT_ID, Instant.now(), Instant.now(), Instant.now()));

        // S1 정상 시작
        watchingSessionService.start(WATCHER_ID, CONTENT_ID, S1);

        // when: S2가 잘못된 콘텐츠로 시작을 시도하여 예외 발생
        UUID invalidContentId = UUID.randomUUID();
        when(contentRepository.existsById(invalidContentId)).thenReturn(false);

        assertThatThrownBy(() -> watchingSessionService.start(WATCHER_ID, invalidContentId, S2))
            .isInstanceOf(BusinessException.class);

        // then: S2의 에러 핸들링으로 end(S2)가 호출되더라도 DB는 삭제되지 않아야 함 (S1 보호)
        boolean s2Ended = watchingSessionService.end(WATCHER_ID, S2);
        assertThat(s2Ended).isFalse();
        verify(watchingSessionSnapshotRepository, never()).deleteByWatcherId(WATCHER_ID);

        // S1은 여전히 소유자이므로 정상 종료가 가능해야 함
        boolean s1Ended = watchingSessionService.end(WATCHER_ID, S1);
        assertThat(s1Ended).isTrue();
        verify(watchingSessionSnapshotRepository).deleteByWatcherId(WATCHER_ID);
    }

    @Test
    @DisplayName("동시성: S1 종료(end)와 S2 시작(start) 경합 시 임계구역 락에 의해 실행이 섞이지 않아야 한다")
    void concurrentEndAndStart_doesNotInterleave() throws Exception {
        // given
        String S1 = "session-1";
        String S2 = "session-2";
        mockContentExists(CONTENT_ID);
        mockContentExists(NEW_CONTENT_ID);
        mockUserExists(WATCHER_ID);

        when(watchingSessionSnapshotWriter.upsert(eq(WATCHER_ID), eq(CONTENT_ID), any()))
            .thenReturn(createSnapshotFixture(CONTENT_ID, Instant.now(), Instant.now(), Instant.now()));
        watchingSessionService.start(WATCHER_ID, CONTENT_ID, S1);

        // 실행 순서 추적을 위한 스레드 안전 리스트
        java.util.List<String> executionOrder = synchronizedList(new ArrayList<>());

        // S1의 deleteByWatcherId 처리에 의도적 지연(100ms) 추가
        doAnswer(invocation -> {
            executionOrder.add("DELETE_START");
            Thread.sleep(100);
            executionOrder.add("DELETE_END");
            return null;
        }).when(watchingSessionSnapshotRepository).deleteByWatcherId(WATCHER_ID);

        // S2의 upsert 처리
        doAnswer(invocation -> {
            executionOrder.add("UPSERT_START");
            Thread.sleep(50);
            executionOrder.add("UPSERT_END");
            return createSnapshotFixture(NEW_CONTENT_ID, Instant.now(), Instant.now(), Instant.now());
        }).when(watchingSessionSnapshotWriter).upsert(any(), any(), any());

        ExecutorService executor = newFixedThreadPool(2);
        CountDownLatch startLatch = new java.util.concurrent.CountDownLatch(1);
        CountDownLatch doneLatch = new java.util.concurrent.CountDownLatch(2);

        // when
        // Thread 1: S1의 end 호출
        executor.submit(() -> {
            try {
                startLatch.await(); // 동시 출발
                watchingSessionService.end(WATCHER_ID, S1);
            } catch (Exception ignored) {}
            finally { doneLatch.countDown(); }
        });

        // Thread 2: S2의 start 호출
        executor.submit(() -> {
            try {
                startLatch.await(); // 동시 출발
                watchingSessionService.start(WATCHER_ID, NEW_CONTENT_ID, S2);
            } catch (Exception ignored) {}
            finally { doneLatch.countDown(); }
        });

        startLatch.countDown(); // 두 스레드 동시 실행 시작
        doneLatch.await();      // 모두 완료될 때까지 대기
        executor.shutdown();

        // then
        // 락(Lock)에 의해 둘 중 하나가 완전히 끝난 후 다음 작업이 실행되어야 함
        // (DELETE와 UPSERT 중간에 다른 로직이 끼어들지 않음)
        String joinedOrder = String.join(",", executionOrder);

        boolean atomicOrder1 = joinedOrder.equals("DELETE_START,DELETE_END,UPSERT_START,UPSERT_END");
        boolean atomicOrder2 = joinedOrder.equals("UPSERT_START,UPSERT_END,DELETE_START,DELETE_END");

        assertThat(atomicOrder1 || atomicOrder2)
            .as("작업이 원자적으로 실행되지 않고 중간에 섞임. 실행 로그: " + joinedOrder)
            .isTrue();
    }
}
