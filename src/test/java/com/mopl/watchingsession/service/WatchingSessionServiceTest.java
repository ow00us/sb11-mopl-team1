package com.mopl.watchingsession.service;


import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.mopl.content.entity.Content;
import com.mopl.content.entity.ContentType;
import com.mopl.content.repository.ContentRepository;
import com.mopl.global.exception.BusinessException;
import com.mopl.global.exception.ErrorCode;
import com.mopl.user.entity.User;
import com.mopl.user.repository.UserRepository;
import com.mopl.watchingsession.dto.WatchingSessionDto;
import com.mopl.watchingsession.entity.WatchingSessionSnapshot;
import com.mopl.watchingsession.repository.WatchingSessionSnapshotRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;
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

    private void mockUserAndContentExists(UUID contentId) {
        Content mockContent = mock(Content.class);
        when(mockContent.getId()).thenReturn(contentId);
        when(mockContent.getType()).thenReturn(ContentType.MOVIE);
        when(mockContent.getAverageRating()).thenReturn(BigDecimal.ZERO);
        when(mockContent.getReviewCount()).thenReturn(0L);

        when(contentRepository.existsById(contentId)).thenReturn(true);
        when(contentRepository.findById(contentId)).thenReturn(Optional.of(mockContent));

        User mockUser = mock(User.class);
        when(mockUser.getId()).thenReturn(WATCHER_ID);
        when(userRepository.findById(WATCHER_ID)).thenReturn(Optional.of(mockUser));
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

    @Test
    @DisplayName("활성 세션 없으면 새 세션 생성")
    void start_success_whenNoActiveSession() {
        // given
        mockUserAndContentExists(CONTENT_ID);

        WatchingSessionSnapshot created = createSnapshotFixture(
            CONTENT_ID, FIRST_CREATED_AT, FIRST_CREATED_AT, FIRST_CREATED_AT.plus(1, ChronoUnit.HOURS)
        );
        when(watchingSessionSnapshotWriter.upsert(eq(WATCHER_ID), eq(CONTENT_ID), any())).thenReturn(created);

        // when
        WatchingSessionDto response = watchingSessionService.start(WATCHER_ID, CONTENT_ID);

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
        mockUserAndContentExists(CONTENT_ID);

        Instant now = Instant.now();

        WatchingSessionSnapshot afterRetry = createSnapshotFixture(
            CONTENT_ID, FIRST_CREATED_AT, now, now.plus(1, ChronoUnit.HOURS)
        );

        when(watchingSessionSnapshotWriter.upsert(eq(WATCHER_ID), eq(CONTENT_ID), any()))
            .thenThrow(new DataIntegrityViolationException("unique violation"))
            .thenReturn(afterRetry);

        // when
        WatchingSessionDto response = watchingSessionService.start(WATCHER_ID, CONTENT_ID);

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
        mockUserAndContentExists(CONTENT_ID);
        mockUserAndContentExists(NEW_CONTENT_ID);

        Instant now = Instant.now();

        WatchingSessionSnapshot upserted = createSnapshotFixture(
            NEW_CONTENT_ID, FIRST_CREATED_AT, now, now.plus(1, ChronoUnit.HOURS)
        );

        when(watchingSessionSnapshotWriter.upsert(eq(WATCHER_ID), eq(CONTENT_ID), any()))
            .thenReturn(upserted);

        // when
        WatchingSessionDto response = watchingSessionService.start(WATCHER_ID, CONTENT_ID);

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
        assertThatThrownBy(() -> watchingSessionService.start(WATCHER_ID, CONTENT_ID))
            .isInstanceOf(BusinessException.class)
            .extracting("errorCode")
            .isEqualTo(ErrorCode.CONTENT_NOT_FOUND);

        verify(watchingSessionSnapshotWriter, never()).upsert(any(), any(), any());
    }

    @Test
    @DisplayName("종료 시 활성 세션 유무와 상관없이 예외 없이 삭제 시도")
    void end_success_isIdempotent() {
        // when & then
        watchingSessionService.end(WATCHER_ID);

        verify(watchingSessionSnapshotRepository).deleteByWatcherId(WATCHER_ID);
    }

    @Test
    @DisplayName("활성 세션이 있으면 조회 결과 반환")
    void get_success_whenActiveSessionExists() {
        // given
        mockUserAndContentExists(CONTENT_ID);

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
}
