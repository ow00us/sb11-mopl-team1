package com.mopl.watchingsession.scheduler;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.mopl.watchingsession.entity.WatchingSessionSnapshot;
import com.mopl.watchingsession.presence.WatchingSessionPresenceWriter;
import com.mopl.watchingsession.repository.WatchingSessionSnapshotRepository;
import com.mopl.watchingsession.service.WatchingSessionSnapshotWriter;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
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

    private static final int SWEEP_BATCH_SIZE = 500; // 프로덕션 상수와 동일

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
        when(watchingSessionSnapshotRepository.findExpiredCandidatesAfterCursor(
            any(), isNull(), isNull(), any(Pageable.class)))
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
        when(watchingSessionSnapshotRepository.findExpiredCandidatesAfterCursor(
            any(), isNull(), isNull(), any(Pageable.class)))
            .thenReturn(List.of());

        sweeper.sweep();

        verify(watchingSessionPresenceWriter, never()).findExistingWatcherIds(anyCollection());
        verify(watchingSessionSnapshotWriter, never()).deleteAllByIdInAndExpiresAtBefore(any(), any());
    }

    @Test
    @DisplayName("모든 후보의 presence가 살아있으면 삭제를 호출하지 않는다")
    void sweep_skipsDelete_whenAllCandidatesAlive() {
        WatchingSessionSnapshot alive = fixture(watcherAlive);
        when(watchingSessionSnapshotRepository.findExpiredCandidatesAfterCursor(
            any(), isNull(), isNull(), any(Pageable.class)))
            .thenReturn(List.of(alive));
        when(watchingSessionPresenceWriter.findExistingWatcherIds(anyCollection()))
            .thenReturn(Set.of(watcherAlive));

        sweeper.sweep();

        verify(watchingSessionSnapshotWriter, never()).deleteAllByIdInAndExpiresAtBefore(any(), any());
    }

    @Test
    @DisplayName("Repository/Writer가 예외를 던져도 격리되어 스케줄러 자체는 죽지 않는다")
    void sweep_isolatesFailure() {
        when(watchingSessionSnapshotRepository.findExpiredCandidatesAfterCursor(
            any(), isNull(), isNull(), any(Pageable.class)))
            .thenThrow(new RuntimeException("DB 연결 끊김"));

        assertThatCode(() -> sweeper.sweep()).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("한 배치가 전부 활성 presence여도 커서가 전진해, 다음 스윕에서 그 뒤의 고아 후보가 처리된다")
    void sweep_advancesCursor_soLaterOrphanIsProcessedInNextRun() {
        List<WatchingSessionSnapshot> firstBatch = new ArrayList<>();
        for (int i = 0; i < SWEEP_BATCH_SIZE; i++) {
            firstBatch.add(fixture(UUID.randomUUID()));
        }
        WatchingSessionSnapshot lastOfFirstBatch = firstBatch.get(firstBatch.size() - 1);
        Set<UUID> aliveWatchers = firstBatch.stream()
            .map(WatchingSessionSnapshot::getWatcherId).collect(Collectors.toSet());
        WatchingSessionSnapshot orphan = fixture(watcherGhost);

        when(watchingSessionSnapshotRepository.findExpiredCandidatesAfterCursor(
            any(), isNull(), isNull(), any(Pageable.class)))
            .thenReturn(firstBatch);
        when(watchingSessionPresenceWriter.findExistingWatcherIds(anyCollection()))
            .thenReturn(aliveWatchers);

        sweeper.sweep(); // 1회차: 전부 활성이라 삭제 없이 커서만 전진

        verify(watchingSessionSnapshotWriter, never()).deleteAllByIdInAndExpiresAtBefore(any(), any());

        when(watchingSessionSnapshotRepository.findExpiredCandidatesAfterCursor(
            any(), eq(lastOfFirstBatch.getExpiresAt()), eq(lastOfFirstBatch.getId()), any(Pageable.class)))
            .thenReturn(List.of(orphan));
        when(watchingSessionPresenceWriter.findExistingWatcherIds(anyCollection()))
            .thenReturn(Set.of());

        sweeper.sweep(); // 2회차: 전진한 커서로 그 다음 후보(고아)를 조회해 삭제

        verify(watchingSessionSnapshotWriter).deleteAllByIdInAndExpiresAtBefore(
            eq(List.of(orphan.getId())), any());
    }

    @Test
    @DisplayName("배치가 끝(배치 크기 미만)에 도달하면 다음 스윕은 처음부터 다시 조회한다 (순환)")
    void sweep_resetsCursorToStart_whenBatchIsShorterThanPageSize() {
        WatchingSessionSnapshot onlyCandidate = fixture(watcherAlive);
        when(watchingSessionSnapshotRepository.findExpiredCandidatesAfterCursor(
            any(), isNull(), isNull(), any(Pageable.class)))
            .thenReturn(List.of(onlyCandidate))
            .thenReturn(List.of());
        when(watchingSessionPresenceWriter.findExistingWatcherIds(anyCollection()))
            .thenReturn(Set.of(watcherAlive));

        sweeper.sweep();
        sweeper.sweep();

        verify(watchingSessionSnapshotRepository, times(2))
            .findExpiredCandidatesAfterCursor(any(), isNull(), isNull(), any(Pageable.class));
    }
}
