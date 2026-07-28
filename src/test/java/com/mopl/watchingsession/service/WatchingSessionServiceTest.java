package com.mopl.watchingsession.service;


import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.mopl.content.repository.ContentRepository;
import com.mopl.global.exception.BusinessException;
import com.mopl.global.exception.ErrorCode;
import com.mopl.watchingsession.dto.WatchingSessionDto;
import com.mopl.watchingsession.entity.WatchingSessionSnapshot;
import com.mopl.watchingsession.repository.WatchingSessionSnapshotRepository;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
public class WatchingSessionServiceTest {

    @Mock
    WatchingSessionSnapshotRepository watchingSessionSnapshotRepository;

    @Mock
    ContentRepository contentRepository;

    @InjectMocks
    WatchingSessionService watchingSessionService;

    private static final UUID WATCHER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID CONTENT_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");

    @Test
    @DisplayName("활성 세션 없으면 새 세션 생성")
    void start_success_whenNoActiveSession() {
        // given
        when(contentRepository.existsById(CONTENT_ID)).thenReturn(true);
        when(watchingSessionSnapshotRepository.findByWatcherId(WATCHER_ID)).thenReturn(Optional.empty());
        when(watchingSessionSnapshotRepository.save(any(WatchingSessionSnapshot.class)))
            .thenAnswer(invocation -> invocation.getArgument(0));

        // when
        WatchingSessionDto response = watchingSessionService.start(WATCHER_ID, CONTENT_ID);

        // then
        assertThat(response.watcherId()).isEqualTo(WATCHER_ID);
        assertThat(response.contentId()).isEqualTo(CONTENT_ID);
        assertThat(response.expiresAt()).isAfter(Instant.now());

        ArgumentCaptor<WatchingSessionSnapshot> captor = ArgumentCaptor.forClass(
            WatchingSessionSnapshot.class);
        verify(watchingSessionSnapshotRepository).save(captor.capture());
        assertThat(captor.getValue().getWatcherId()).isEqualTo(WATCHER_ID);
    }

    @Test
    @DisplayName("다른 콘텐츠를 시청 중이던 사용자가 새로 시작하면 기존 세션을 교체")
    void start_success_replaceExistingSession() {
        // given
        UUID previousContentId = UUID.fromString("33333333-3333-3333-3333-333333333333");
        WatchingSessionSnapshot existing = WatchingSessionSnapshot.builder()
            .watcherId(WATCHER_ID)
            .contentId(previousContentId)
            .expiresAt(Instant.now().plusSeconds(60))
            .build();

        when(contentRepository.existsById(CONTENT_ID)).thenReturn(true);
        when(watchingSessionSnapshotRepository.findByWatcherId(WATCHER_ID)).thenReturn(Optional.of(existing));
        when(watchingSessionSnapshotRepository.save(any(WatchingSessionSnapshot.class)))
            .thenAnswer(invocation -> invocation.getArgument(0));

        // when
        WatchingSessionDto response = watchingSessionService.start(WATCHER_ID, CONTENT_ID);

        // then - 같은 객체, 기존 행 갱신
        assertThat(response.contentId()).isEqualTo(CONTENT_ID);
        assertThat(existing.getContentId()).isEqualTo(CONTENT_ID);
        verify(watchingSessionSnapshotRepository).save(existing);
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
        assertThat(result.get().contentId()).isEqualTo(CONTENT_ID);
    }

    @Test
    @DisplayName("활성 세션이 없으면 빈 Optional 반환")
    void get_success_whenNoActiveSession() {
        // given
        when(watchingSessionSnapshotRepository.findByWatcherId(WATCHER_ID)).thenReturn(Optional.empty());

        // when
        Optional<WatchingSessionDto> result = watchingSessionService.get(WATCHER_ID);

        // then
        assertThat(result).isEmpty();
    }
}
