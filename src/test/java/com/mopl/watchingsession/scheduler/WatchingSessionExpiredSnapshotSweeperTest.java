package com.mopl.watchingsession.scheduler;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.mopl.watchingsession.service.WatchingSessionSnapshotWriter;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class WatchingSessionExpiredSnapshotSweeperTest {

    @Mock
    private WatchingSessionSnapshotWriter watchingSessionSnapshotWriter;

    private WatchingSessionExpiredSnapshotSweeper sweeper;

    @BeforeEach
    void setUp() {
        sweeper = new WatchingSessionExpiredSnapshotSweeper(watchingSessionSnapshotWriter);
    }

    @Test
    @DisplayName("sweep()은 현재 시각 기준으로 만료된 스냅샷 삭제를 위임한다")
    void sweep_delegatesDeleteExpiredBefore() {
        when(watchingSessionSnapshotWriter.deleteExpiredBefore(any())).thenReturn(3);

        sweeper.sweep();

        verify(watchingSessionSnapshotWriter).deleteExpiredBefore(any(Instant.class));
    }

    @Test
    @DisplayName("삭제 대상이 없어도 예외 없이 끝난다")
    void sweep_doesNotThrow_whenNothingDeleted() {
        when(watchingSessionSnapshotWriter.deleteExpiredBefore(any())).thenReturn(0);

        assertThatCode(() -> sweeper.sweep()).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("Writer가 예외를 던져도 격리되어 스케줄러 자체는 죽지 않는다 (다음 주기 실행을 막으면 안 됨)")
    void sweep_isolatesWriterFailure() {
        when(watchingSessionSnapshotWriter.deleteExpiredBefore(any()))
            .thenThrow(new RuntimeException("DB 연결 끊김"));

        assertThatCode(() -> sweeper.sweep()).doesNotThrowAnyException();
    }
}
