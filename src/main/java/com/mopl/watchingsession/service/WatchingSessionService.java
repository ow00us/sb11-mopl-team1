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
import lombok.Getter;
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
     * 시청 세션의 소유권을 표현하는 (WebSocket 연결, 구독) 쌍. subscriptionId가 null인 경우(예: 아직 STOMP 구독 이전 경로로 호출되는 경우
     * 등)도 방어적으로 다루기 위해 Objects.equals 기반 equals/hashCode를 record가 자동 생성해준다.
     */
    private record SubscriptionOwner(String sessionId, String subscriptionId) {

    }

    public record ReplacedSession(WatchingSessionDto session, WatchingSessionDto previous) {

    }

    /**
     * start()가 DB 스냅샷을 이미 갱신한 뒤 enrich 단계에서 실패해 보상 삭제까지 마친 경우 던진다.
     * <p>
     * 보상 삭제가 실제로 수행됐다면 그 시점에 "이전 콘텐츠 퇴장"이 실제로 발생한 것이므로, 그 이전 세션(endedPrevious)을 함께 실어 리스너가 해당 방에
     * LEAVE를 브로드캐스트할 수 있게 한다. (브로드캐스트 책임은 성공 경로의 ReplacedSession과 동일하게 리스너가 전담한다)
     * <p>
     * 클라이언트로 나가는 ERROR 프레임은 기존과 동일하게 원인 예외(cause)를 기준으로 만들어야 하므로 BusinessException을 상속하지 않는다.
     */
    @Getter
    public static class StartFailedException extends RuntimeException {

        // 보상 삭제로 실제 종료된 이전 세션, 없었거나 보상 삭제가 수행되지 않았으면 null
        private final transient WatchingSessionDto endedPrevious;

        public StartFailedException(RuntimeException cause, WatchingSessionDto endedPrevious) {
            super(cause.getMessage(), cause);
            this.endedPrevious = endedPrevious;
        }
    }

    // Redis presence + heartbeat/TTL이 들어오기 전 쓰는 임시 고정 TTL
    // TODO(심화필수): Redis 쓰기 모델로 옮기면서 heartbeat 기반 갱신으로 대체
    private static final Duration DEFAULT_SESSION_TTL = Duration.ofMinutes(30);
    private static final String LIKE_ESCAPE_CHAR = "\\";

    private final WatchingSessionSnapshotRepository watchingSessionSnapshotRepository;
    private final ContentRepository contentRepository;
    private final UserRepository userRepository;
    private final WatchingSessionSnapshotWriter watchingSessionSnapshotWriter;

    /**
     * watcherId 기준 "지금 이 세션을 소유한 WebSocket 연결(sessionId)"을 추적한다. 다중 탭/새로고침 시 오래된 연결의 DISCONNECT가 새
     * 연결의 세션을 잘못 지우는 것을 막기 위함.
     * <p>
     * 주의: 이 맵은 단일 서버 인스턴스 전제의 인메모리 구조다. 서버 재시작 시 유실되고 다중 인스턴스 환경에서는 인스턴스마다 별도로 존재해 소유권 판정이 어긋난다.
     * TODO(심화필수): Redis presence(사용자당 활성 세션 1개, 원본)로 이전하면서
     * 이 소유권 판정 자체를 Redis 쪽 구조로 대체할 예정. 그 전까지는 단일 인스턴스 운영을 전제로 한다.
     */
    private final ConcurrentHashMap<UUID, SubscriptionOwner> activeSessions = new ConcurrentHashMap<>();

    // Watcher 단위로 독립적인 원자성을 보장하기 위한 락 맵.
    // 값이 참조 카운트를 함께 들고 있어, 아무도 사용하지 않는 순간(refCount == 0) 엔트리가 제거된다.
    private final ConcurrentHashMap<UUID, WatcherLock> watcherLocks = new ConcurrentHashMap<>();

    /**
     * synchronized 대상 락 객체 + 현재 이 락을 잡았거나 대기 중인 스레드 수.
     * <p>
     * refCount는 항상 ConcurrentHashMap의 키 단위 원자 연산(compute/computeIfPresent) 안에서만 읽고 쓰므로, CHM 내부 동기화가
     * 가시성을 보장한다. 별도 volatile/Atomic이 필요 없다.
     */
    private static final class WatcherLock {

        private int refCount;
    }

    /**
     * 락 객체를 얻으면서 참조 카운트를 1 올린다. 카운트를 올린 뒤에 synchronized에 진입하므로, 임계 구역에 머무는 동안 다른 스레드의 release가 이
     * 엔트리를 제거할 수 없다.
     */
    private WatcherLock acquireWatcherLock(UUID watcherId) {
        return watcherLocks.compute(watcherId, (key, existing) -> {
            WatcherLock lock = (existing != null) ? existing : new WatcherLock();
            lock.refCount++;
            return lock;
        });
    }

    /**
     * 참조 카운트를 1 내리고, 0이 되면 맵에서 제거한다. 반드시 synchronized 블록을 빠져나온 뒤 finally에서 호출해야 한다.
     */
    private void releaseWatcherLock(UUID watcherId) {
        watcherLocks.computeIfPresent(watcherId, (key, lock) -> {
            lock.refCount--;
            return (lock.refCount == 0) ? null : lock;
        });
    }


    // create + update 성격의 메서드 - 독립 트랜잭션을 위해 writer 호출
    public ReplacedSession start(UUID watcherId, UUID contentId, String sessionId,
        String subscriptionId) {

        WatchingSessionSnapshot snapshot;
        WatchingSessionDto previous;

        WatcherLock watcherLock = acquireWatcherLock(watcherId);
        // ★ 임계 구역 시작: 검증, DB 반영, 소유권 갱신을 묶어 원자적으로 처리
        try {
            synchronized (watcherLock) {
                // 검증 먼저 진행 (실패 시 소유권 변경이나 DB 터치 없음)
                validateContentExists(contentId);

                previous = get(watcherId).orElse(null);

                Instant expiresAt = Instant.now().plus(DEFAULT_SESSION_TTL);

                // DB 스냅샷 갱신
                try {
                    snapshot = watchingSessionSnapshotWriter.upsert(watcherId, contentId,
                        expiresAt);
                } catch (DataIntegrityViolationException e) {
                    snapshot = watchingSessionSnapshotWriter.upsert(watcherId, contentId,
                        expiresAt);
                }

                // DB 반영까지 안전하게 성공했을 때 비로소 소유권을 갱신
                activeSessions.put(watcherId, new SubscriptionOwner(sessionId, subscriptionId));
            }
        } finally {
            // validateContentExists()가 CONTENT_NOT_FOUND를 던지는 경로에서도 락 엔트리가 남지 않아야 함
            releaseWatcherLock(watcherId);
        }

        try {
            return new ReplacedSession(enrich(snapshot), previous);
        } catch (RuntimeException e) {
            boolean compensated;
            try {
                compensated = end(watcherId, sessionId, subscriptionId);
            } catch (RuntimeException compensationFailure) {
                e.addSuppressed(compensationFailure);
                throw new StartFailedException(e, null);
            }

            // 보상삭제가 실제로 수행된 경우에만 이전 세션을 전달
            // end()가 false면 소유권이 이미 다른 연결로 넘어간 것이라 스냅샷이 살아 있으므로 퇴장이 아님
            throw new StartFailedException(e, compensated ? previous : null);
        }
    }

    // delete 성격의 메서드
    public boolean end(UUID watcherId, String currentSessionId, String currentSubscriptionId) {

        WatcherLock watcherLock = acquireWatcherLock(watcherId);
        try {
            synchronized (watcherLock) {
                SubscriptionOwner requester = new SubscriptionOwner(currentSessionId,
                    currentSubscriptionId);
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
        } finally {
            releaseWatcherLock(watcherId);
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
        UUID contentId, String watcherNameLike, String cursor, UUID idAfter, int limit,
        String sortBy, String sortDirection
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
            nextCursor = CursorUtils.encodeInstant(last.getCreatedAt());
            nextIdAfter = last.getId();
        }

        long totalCount = watchingSessionSnapshotRepository.countByContentId(contentId,
            escapedWatcherNameLike, now);

        return CursorResponse.of(data, nextCursor, nextIdAfter, hasNext, totalCount, sortBy,
            normalizedSortDirection);
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
