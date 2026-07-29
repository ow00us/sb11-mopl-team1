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

    private static final UUID WATCHER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID CONTENT_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");

    private void mockUserAndContentExists() {
        Content mockContent = mock(Content.class);
        when(mockContent.getId()).thenReturn(CONTENT_ID);
        when(mockContent.getType()).thenReturn(ContentType.MOVIE);
        when(mockContent.getAverageRating()).thenReturn(BigDecimal.ZERO);
        when(mockContent.getReviewCount()).thenReturn(0L);

        when(contentRepository.existsById(CONTENT_ID)).thenReturn(true);
        when(contentRepository.findById(CONTENT_ID)).thenReturn(Optional.of(mockContent));

        User mockUser = mock(User.class);
        when(mockUser.getId()).thenReturn(WATCHER_ID);
        when(userRepository.findById(WATCHER_ID)).thenReturn(Optional.of(mockUser));
    }

    @Test
    @DisplayName("활성 세션 없으면 새 세션 생성")
    void start_success_whenNoActiveSession() {
        // given
        mockUserAndContentExists();

        WatchingSessionSnapshot created = WatchingSessionSnapshot.builder()
                .watcherId(WATCHER_ID)
                .contentId(CONTENT_ID)
                .expiresAt(Instant.now().plusSeconds(60))
                .build();
        when(watchingSessionSnapshotWriter.upsert(eq(WATCHER_ID), eq(CONTENT_ID), any())).thenReturn(created);

        // when
        WatchingSessionDto response = watchingSessionService.start(WATCHER_ID, CONTENT_ID);

        // then
        assertThat(response.watcher().userId()).isEqualTo(WATCHER_ID);
        assertThat(response.content().id()).isEqualTo(CONTENT_ID);
    }

    @Test
    @DisplayName("동시 삽입 경합 시 한 번 재시도해 갱신 결과 반환")
    void start_success_retriesOnConcurrentInsertConflict() {
        // given
        mockUserAndContentExists();

        WatchingSessionSnapshot afterRetry = WatchingSessionSnapshot.builder()
            .watcherId(WATCHER_ID).contentId(CONTENT_ID).expiresAt(Instant.now().plusSeconds(60)).build();

        when(watchingSessionSnapshotWriter.upsert(eq(WATCHER_ID), eq(CONTENT_ID), any()))
            .thenThrow(new DataIntegrityViolationException("unique violation"))
            .thenReturn(afterRetry);

        // when
        WatchingSessionDto response = watchingSessionService.start(WATCHER_ID, CONTENT_ID);

        // then
        assertThat(response.content().id()).isEqualTo(CONTENT_ID);
        verify(watchingSessionSnapshotWriter, times(2)).upsert(eq(WATCHER_ID), eq(CONTENT_ID), any());
    }

    @Test
    @DisplayName("다른 콘텐츠를 시청 중이던 사용자가 새로 시작하면 기존 세션을 교체")
    void start_success_replaceExistingSession() {
        // given
        mockUserAndContentExists();

        WatchingSessionSnapshot existing = WatchingSessionSnapshot.builder()
            .watcherId(WATCHER_ID)
            .contentId(CONTENT_ID)
            .expiresAt(Instant.now().plusSeconds(60))
            .build();

        when(watchingSessionSnapshotWriter.upsert(eq(WATCHER_ID), eq(CONTENT_ID), any()))
            .thenReturn(existing);

        // when
        WatchingSessionDto response = watchingSessionService.start(WATCHER_ID, CONTENT_ID);

        // then - 같은 객체, 기존 행 갱신
        assertThat(response.content().id()).isEqualTo(CONTENT_ID);
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

        verify(watchingSessionSnapshotRepository, never()).save(any());
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
        mockUserAndContentExists();

        WatchingSessionSnapshot snapshot = WatchingSessionSnapshot.builder()
            .watcherId(WATCHER_ID)
            .contentId(CONTENT_ID)
            .expiresAt(Instant.now().plusSeconds(60))
            .build();
        when(watchingSessionSnapshotRepository.findByWatcherId(WATCHER_ID)).thenReturn(Optional.of(snapshot));

        // when
        Optional<WatchingSessionDto> result = watchingSessionService.get(WATCHER_ID);

        // then
        assertThat(result).isPresent();
        assertThat(result.get().content().id()).isEqualTo(CONTENT_ID);
    }

    @Test
    @DisplayName("활성 세션이 없으면 빈 Optional 반환")
    void get_success_whenNoActiveSession() {
        // given
        WatchingSessionSnapshot expired = WatchingSessionSnapshot.builder()
                .watcherId(WATCHER_ID)
                .contentId(CONTENT_ID)
                .expiresAt(Instant.now().minusSeconds(1))
                .build();
        when(watchingSessionSnapshotRepository.findByWatcherId(WATCHER_ID)).thenReturn(Optional.of(expired));

        // when
        Optional<WatchingSessionDto> result = watchingSessionService.get(WATCHER_ID);

        // then
        assertThat(result).isEmpty();
    }
}
