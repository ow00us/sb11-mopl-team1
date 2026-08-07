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
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.stream.Collectors;
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

    /**
     * 시청 세션의 소유권을 표현하는 (WebSocket 연결, 구독) 쌍.
     * subscriptionId가 null인 경우(예: 아직 STOMP 구독 이전 경로로 호출되는 경우 등)도
     * 방어적으로 다루기 위해 Objects.equals 기반 equals/hashCode를 record가 자동 생성해준다.
     */
    private record SubscriptionOwner(String sessionId, String subscriptionId) {}

    public record ReplacedSession(WatchingSessionDto session, WatchingSessionDto previous) {}

    // Redis presence + heartbeat/TTL이 들어오기 전 쓰는 임시 고정 TTL
    // TODO(심화필수): Redis 쓰기 모델로 옮기면서 heartbeat 기반 갱신으로 대체
    private static final Duration DEFAULT_SESSION_TTL = Duration.ofMinutes(30);
    private static final String LIKE_ESCAPE_CHAR = "\\";

    private final WatchingSessionSnapshotRepository watchingSessionSnapshotRepository;
    private final ContentRepository contentRepository;
    private final UserRepository userRepository;
    private final WatchingSessionSnapshotWriter watchingSessionSnapshotWriter;

    /**
     * watcherId 기준 "지금 이 세션을 소유한 WebSocket 연결(sessionId)"을 추적한다.
     * 다중 탭/새로고침 시 오래된 연결의 DISCONNECT가 새 연결의 세션을 잘못 지우는 것을 막기 위함.
     *
     * 주의: 이 맵은 단일 서버 인스턴스 전제의 인메모리 구조다. 서버 재시작 시 유실되고
     * 다중 인스턴스 환경에서는 인스턴스마다 별도로 존재해 소유권 판정이 어긋난다.
     * TODO(심화필수): Redis presence(사용자당 활성 세션 1개, 원본)로 이전하면서
     * 이 소유권 판정 자체를 Redis 쪽 구조로 대체할 예정. 그 전까지는 단일 인스턴스 운영을 전제로 한다.
     */
    private final ConcurrentHashMap<UUID, SubscriptionOwner> activeSessions = new ConcurrentHashMap<>();

    // ★ 추가됨: Watcher 단위로 독립적인 원자성을 보장하기 위한 락 맵
    private final ConcurrentHashMap<UUID, Object> watcherLocks = new ConcurrentHashMap<>();

    private Object getWatcherLock(UUID watcherId) {
        return watcherLocks.computeIfAbsent(watcherId, k -> new Object());
    }

    // create + update 성격의 메서드 - 독립 트랜잭션을 위해 writer 호출
    public ReplacedSession start(UUID watcherId, UUID contentId, String sessionId, String subscriptionId) {

        WatchingSessionSnapshot snapshot;
        WatchingSessionDto previous;

        // ★ 임계 구역 시작: 검증, DB 반영, 소유권 갱신을 묶어 원자적으로 처리
        synchronized (getWatcherLock(watcherId)) {
            // 검증 먼저 진행 (실패 시 소유권 변경이나 DB 터치 없음)
            validateContentExists(contentId);

            previous = get(watcherId).orElse(null);

            Instant expiresAt = Instant.now().plus(DEFAULT_SESSION_TTL);

            // DB 스냅샷 갱신
            try {
                snapshot = watchingSessionSnapshotWriter.upsert(watcherId, contentId, expiresAt);
            } catch (DataIntegrityViolationException e) {
                snapshot = watchingSessionSnapshotWriter.upsert(watcherId, contentId, expiresAt);
            }

            // DB 반영까지 안전하게 성공했을 때 비로소 소유권을 갱신
            activeSessions.put(watcherId, new SubscriptionOwner(sessionId, subscriptionId));
        }

        try {
            return new ReplacedSession(enrich(snapshot), previous);
        } catch (RuntimeException e) {
            end(watcherId, sessionId, subscriptionId);
            throw e;
        }
    }

    // delete 성격의 메서드
    public boolean end(UUID watcherId, String currentSessionId, String currentSubscriptionId) {
        synchronized (getWatcherLock(watcherId)) {
            SubscriptionOwner requester = new SubscriptionOwner(currentSessionId, currentSubscriptionId);
            // 확인만 먼저 수행 (메모리에서 지우지는 않음)
            if (!requester.equals(activeSessions.get(watcherId))) {
                return false;
            }

            // DB 삭제 (여기서 예외가 터지면 소유권은 그대로 유지됨)
            watchingSessionSnapshotWriter.delete(watcherId);

            // DB 삭제까지 완벽히 성공한 후 메모리 소유권 정리
            activeSessions.remove(watcherId);
            return true;
        }
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
        Content content = contentRepository.findById(contentId)
            .orElseThrow(() -> new BusinessException(ErrorCode.CONTENT_NOT_FOUND));
        validateSortBy(sortBy);
        validateSortDirection(sortDirection);
        validateCursorPair(cursor, idAfter);

        String escapedWatcherNameLike = escapeLikePattern(watcherNameLike);

        Instant now = Instant.now();
        boolean ascending = "ASCENDING".equalsIgnoreCase(sortDirection);
        String normalizedSortDirection = ascending ? "ASCENDING" : "DESCENDING";
        Pageable pageable = PageRequest.of(0, limit + 1);

        List<WatchingSessionSnapshot> rows;
        if (cursor == null) {
            rows = ascending
                ? watchingSessionSnapshotRepository.findByContentIdFirstPageAsc(
                    contentId, escapedWatcherNameLike, now, pageable)
                : watchingSessionSnapshotRepository.findByContentIdFirstPageDesc(
                    contentId, escapedWatcherNameLike, now, pageable);
        } else {
            Instant cursorValue;
            try {
                cursorValue = CursorUtils.decodeAsInstant(cursor);
            } catch (RuntimeException e) {
                throw new BusinessException(ErrorCode.INVALID_INPUT);
            }
            rows = ascending
                ? watchingSessionSnapshotRepository.findByContentIdAfterAsc(
                    contentId, escapedWatcherNameLike, now, cursorValue, idAfter, pageable)
                : watchingSessionSnapshotRepository.findByContentIdAfterDesc(
                    contentId, escapedWatcherNameLike, now, cursorValue, idAfter, pageable);
        }

        boolean hasNext = rows.size() > limit;
        List<WatchingSessionSnapshot> page = hasNext ? rows.subList(0, limit) : rows;

        Map<UUID, User> watchers = userRepository
            .findAllById(page.stream().map(WatchingSessionSnapshot::getWatcherId).toList())
            .stream()
            .collect(Collectors.toMap(User::getId, Function.identity()));

        List<WatchingSessionDto> data = page.stream()
            .map(s -> {
                User watcher = watchers.get(s.getWatcherId());
                if (watcher == null) {
                    throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
                }
                return WatchingSessionDto.from(s, watcher, content);
            })
            .toList();

        String nextCursor = null;
        UUID nextIdAfter = null;
        if (hasNext && !page.isEmpty()) {
            WatchingSessionSnapshot last = page.get(page.size() - 1);
            nextCursor = CursorUtils.encodeInstant(last.getUpdatedAt());
            nextIdAfter = last.getId();
        }

        long totalCount = watchingSessionSnapshotRepository.countByContentId(contentId, escapedWatcherNameLike, now);

        return CursorResponse.of(data, nextCursor, nextIdAfter, hasNext, totalCount, sortBy, normalizedSortDirection);
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

    private void validateSortDirection(String sortDirection) {
        if (!"ASCENDING".equalsIgnoreCase(sortDirection)
        && !"DESCENDING".equalsIgnoreCase(sortDirection)) {
            throw new BusinessException(ErrorCode.INVALID_INPUT);
        }
    }

    // 이스케이프 헬퍼 메서드
    private String escapeLikePattern(String value) {
        if (value == null) {
            return null;
        }
        return value
            .replace(LIKE_ESCAPE_CHAR, LIKE_ESCAPE_CHAR + LIKE_ESCAPE_CHAR)
            .replace("%", LIKE_ESCAPE_CHAR + "%")
            .replace("_", LIKE_ESCAPE_CHAR + "_");
    }

}
