package com.mopl.watchingsession.service;

import com.mopl.content.repository.ContentRepository;
import com.mopl.global.exception.BusinessException;
import com.mopl.global.exception.ErrorCode;
import com.mopl.watchingsession.dto.WatchingSessionDto;
import com.mopl.watchingsession.entity.WatchingSessionSnapshot;
import com.mopl.watchingsession.repository.WatchingSessionSnapshotRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class WatchingSessionService {

    // Redis presence + heartbeat/TTL이 들어오기 전 쓰는 임시 고정 TTL
    // TODO(심화필수): Redis 쓰기 모델로 옮기면서 heartbeat 기반 갱신으로 대체
    private static final Duration DEFAULT_SESSION_TTL = Duration.ofMinutes(30);

    private final WatchingSessionSnapshotRepository watchingSessionSnapshotRepository;
    private final ContentRepository contentRepository;

    // create + update 성격의 메서드
    @Transactional
    public WatchingSessionDto start(UUID watcherId, UUID contentId) {
        validateContentExists(contentId);

        Instant expiresAt = Instant.now().plus(DEFAULT_SESSION_TTL);

        // 스냅샷이 있으면 기존 세션 갱신, 없으면 생성
        WatchingSessionSnapshot snapshot = watchingSessionSnapshotRepository.findByWatcherId(watcherId)
            .map(existing -> {
                existing.refresh(contentId, expiresAt);
                return existing;
            })
            .orElseGet(() -> WatchingSessionSnapshot.builder()
                .watcherId(watcherId)
                .contentId(contentId)
                .expiresAt(expiresAt)
                .build());

        return WatchingSessionDto.from(watchingSessionSnapshotRepository.save(snapshot));
    }

    // delete 성격의 메서드
    @Transactional
    public void end(UUID watcherId) {
        watchingSessionSnapshotRepository.deleteByWatcherId(watcherId);
    }

    // read 성격의 메서드
    public Optional<WatchingSessionDto> get(UUID watcherId) {
        return watchingSessionSnapshotRepository.findByWatcherId(watcherId)
            .map(WatchingSessionDto::from);
    }

    // 콘텐츠 존재여부 확인하는 헬퍼 메서드
    private void validateContentExists(UUID contentId) {
        if (!contentRepository.existsById(contentId)) {
            throw new BusinessException(ErrorCode.CONTENT_NOT_FOUND);
        }
    }

}
