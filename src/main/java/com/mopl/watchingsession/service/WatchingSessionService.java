package com.mopl.watchingsession.service;

import com.mopl.content.entity.Content;
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
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
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
    private final UserRepository userRepository;
    private final WatchingSessionSnapshotWriter watchingSessionSnapshotWriter;

    // create + update 성격의 메서드 - 독립 트랜잭션을 위해 writer 호출
    public WatchingSessionDto start(UUID watcherId, UUID contentId) {
        validateContentExists(contentId);

        Instant expiresAt = Instant.now().plus(DEFAULT_SESSION_TTL);

        WatchingSessionSnapshot snapshot;
        // 스냅샷이 있으면 기존 세션 갱신, 없으면 생성
        try {
            snapshot = watchingSessionSnapshotWriter.upsert(watcherId, contentId, expiresAt);
        } catch (DataIntegrityViolationException e) {
            // 동시 요청이 먼저 삽입에 성공한 경우 새 트랜잭션에서 한번만 재시도
            snapshot = watchingSessionSnapshotWriter.upsert(watcherId, contentId, expiresAt);
        }

        return enrich(snapshot);
    }

    // delete 성격의 메서드
    @Transactional
    public void end(UUID watcherId) {
        watchingSessionSnapshotRepository.deleteByWatcherId(watcherId);
    }

    // read 성격의 메서드
    public Optional<WatchingSessionDto> get(UUID watcherId) {
        return watchingSessionSnapshotRepository.findByWatcherId(watcherId)
            .filter(snapshot -> !snapshot.isExpired(Instant.now()))
            .map(this::enrich);
    }

    // 콘텐츠 존재여부 확인하는 헬퍼 메서드
    private void validateContentExists(UUID contentId) {
        if (!contentRepository.existsById(contentId)) {
            throw new BusinessException(ErrorCode.CONTENT_NOT_FOUND);
        }
    }

    // 스냅샷을 dto로 변환. 이때 watcher/content가 없으면 예외 던짐
    private WatchingSessionDto enrich(WatchingSessionSnapshot snapshot) {
        User watcher = userRepository.findById(snapshot.getWatcherId())
            .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
        Content content = contentRepository.findById(snapshot.getContentId())
            .orElseThrow(() -> new BusinessException(ErrorCode.CONTENT_NOT_FOUND));
        return WatchingSessionDto.from(snapshot, watcher, content);
    }

    // 커서 페이지네이션 조회
    public CursorResponse<WatchingSessionDto> getListByContent(
        UUID contentId, String watcherNameLike, String cursor, UUID idAfter, int limit, String sortBy, String sortDirection
    ) {
        validateContentExists(contentId);
        validateSortBy(sortBy);
        validateCursorPair(cursor, idAfter);

        Instant now = Instant.now();
        boolean ascending = "ASCENDING".equalsIgnoreCase(sortDirection);
        Pageable pageable = PageRequest.of(0, limit + 1);

        List<WatchingSessionSnapshot> rows;
        if (cursor == null) {
            rows = ascending
                ? watchingSessionSnapshotRepository.findByContentIdFirstPageAsc(
                    contentId, watcherNameLike, now, pageable)
                : watchingSessionSnapshotRepository.findByContentIdFirstPageDesc(
                    contentId, watcherNameLike, now, pageable);
        } else {
            Instant cursorValue = CursorUtils.decodeAsInstant(cursor);
            rows = ascending
                ? watchingSessionSnapshotRepository.findByContentIdAfterAsc(
                    contentId, watcherNameLike, now, cursorValue, idAfter, pageable)
                : watchingSessionSnapshotRepository.findByContentIdAfterDesc(
                    contentId, watcherNameLike, now, cursorValue, idAfter, pageable);
        }

        boolean hasNext = rows.size() > limit;
        List<WatchingSessionSnapshot> page = hasNext ? rows.subList(0, limit) : rows;

        List<WatchingSessionDto> data = page.stream()
            .map(this::enrich)
            .toList();

        String nextCursor = null;
        UUID nextIdAfter = null;
        if (hasNext && !page.isEmpty()) {
            WatchingSessionSnapshot last = page.get(page.size() - 1);
            nextCursor = CursorUtils.encodeInstant(last.getUpdatedAt());
            nextIdAfter = last.getId();
        }

        long totalCount = watchingSessionSnapshotRepository.countByContentId(contentId, watcherNameLike, now);

        return CursorResponse.of(data, nextCursor, nextIdAfter, hasNext, totalCount, sortBy, sortDirection);
    }

    private void validateSortBy(String sortBy) {
        if (!"createdAt".equals(sortBy)) {
            throw new BusinessException(ErrorCode.INVALID_INPUT);
        }
    }

    private void validateCursorPair(String cursor, UUID idAfter) {
        boolean cursorPresent = cursor != null;
        boolean idAfterPresent = idAfter != null;
        if (cursorPresent != idAfterPresent) {
            throw new BusinessException(ErrorCode.INVALID_INPUT);
        }
    }

}
