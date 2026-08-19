package com.mopl.watchingsession.scheduler;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.mopl.watchingsession.entity.WatchingSessionSnapshot;
import com.mopl.watchingsession.presence.WatchingSessionPresenceWriter;
import com.mopl.watchingsession.repository.WatchingSessionSnapshotRepository;
import com.mopl.watchingsession.service.WatchingSessionSnapshotWriter;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class WatchingSessionExpiredSnapshotSweeperTest {

    @Mock
    private WatchingSessionSnapshotRepository watchingSessionSnapshotRepository;

    @Mock
    private WatchingSessionSnapshotWriter watchingSessionSnapshotWriter;

    @Mock
    private WatchingSessionPresenceWriter watchingSessionPresenceWriter;

    private WatchingSessionExpiredSnapshotSweeper sweeper;

    private final UUID watcherAlive = UUID.randomUUID();
    private final UUID watcherGhost = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        sweeper = new WatchingSessionExpiredSnapshotSweeper(
            watchingSessionSnapshotWriter, watchingSessionPresenceWriter, watchingSessionSnapshotRepository);
    }

    private WatchingSessionSnapshot fixture(UUID watcherId) {
        WatchingSessionSnapshot snapshot = WatchingSessionSnapshot.builder()
            .watcherId(watcherId)
            .contentId(UUID.randomUUID())
            .expiresAt(Instant.now().minusSeconds(60))
            .build();
        ReflectionTestUtils.setField(snapshot, "id", UUID.randomUUID());
        return snapshot;
    }

    @Test
    @DisplayName("presence가 살아있는 후보는 건너뛰고, 없는 후보만 삭제 대상으로 넘긴다")
    void sweep_deletesOnlyOrphans_skipsAlivePresence() {
        WatchingSessionSnapshot alive = fixture(watcherAlive);
        WatchingSessionSnapshot ghost = fixture(watcherGhost);
        when(watchingSessionSnapshotRepository.findExpiredCandidates(any(), any(Pageable.class)))
            .thenReturn(List.of(alive, ghost));
        when(watchingSessionPresenceWriter.findExistingWatcherIds(anyCollection()))
            .thenReturn(Set.of(watcherAlive));

        sweeper.sweep();

        verify(watchingSessionSnapshotWriter).deleteAllByIdInAndExpiresAtBefore(
            eq(List.of(ghost.getId())), any());
    }

    @Test
    @DisplayName("후보가 없으면 presence 확인이나 삭제를 시도하지 않는다")
    void sweep_doesNothing_whenNoCandidates() {
        when(watchingSessionSnapshotRepository.findExpiredCandidates(any(), any(Pageable.class)))
            .thenReturn(List.of());

        sweeper.sweep();

        verify(watchingSessionPresenceWriter, never()).findExistingWatcherIds(anyCollection());
        verify(watchingSessionSnapshotWriter, never()).deleteAllByIdInAndExpiresAtBefore(any(), any());
    }

    @Test
    @DisplayName("모든 후보의 presence가 살아있으면 삭제를 호출하지 않는다")
    void sweep_skipsDelete_whenAllCandidatesAlive() {
        WatchingSessionSnapshot alive = fixture(watcherAlive);
        when(watchingSessionSnapshotRepository.findExpiredCandidates(any(), any(Pageable.class)))
            .thenReturn(List.of(alive));
        when(watchingSessionPresenceWriter.findExistingWatcherIds(anyCollection()))
            .thenReturn(Set.of(watcherAlive));

        sweeper.sweep();

        verify(watchingSessionSnapshotWriter, never()).deleteAllByIdInAndExpiresAtBefore(any(), any());
    }

    @Test
    @DisplayName("Repository/Writer가 예외를 던져도 격리되어 스케줄러 자체는 죽지 않는다")
    void sweep_isolatesFailure() {
        when(watchingSessionSnapshotRepository.findExpiredCandidates(any(), any(Pageable.class)))
            .thenThrow(new RuntimeException("DB 연결 끊김"));

        assertThatCode(() -> sweeper.sweep()).doesNotThrowAnyException();
    }
}
