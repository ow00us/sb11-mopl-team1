package com.mopl.watchingsession.service;

import static com.mopl.global.util.InstantPrecisionUtils.normalizeToMicros;

import com.mopl.content.entity.Content;
import com.mopl.content.repository.ContentRepository;
import com.mopl.global.common.CursorResponse;
import com.mopl.global.exception.BusinessException;
import com.mopl.global.exception.ErrorCode;
import com.mopl.global.util.CursorUtils;
import com.mopl.global.util.DbConflictUtils;
import com.mopl.user.entity.User;
import com.mopl.user.repository.UserRepository;
import com.mopl.watchingsession.config.WatchingSessionProperties;
import com.mopl.watchingsession.dto.WatchingSessionDto;
import com.mopl.watchingsession.entity.WatchingSessionSnapshot;
import com.mopl.watchingsession.presence.WatchingPresence;
import com.mopl.watchingsession.presence.WatchingSessionPresenceWriter;
import com.mopl.watchingsession.presence.WatchingSessionPresenceWriter.DeletedSnapshot;
import com.mopl.watchingsession.repository.WatchingSessionSnapshotRepository;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class WatchingSessionService {

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

    private final WatchingSessionProperties watchingSessionProperties;

    private static final String LIKE_ESCAPE_CHAR = "\\";

    private final WatchingSessionSnapshotRepository watchingSessionSnapshotRepository;
    private final ContentRepository contentRepository;
    private final UserRepository userRepository;
    private final WatchingSessionSnapshotWriter watchingSessionSnapshotWriter;
    private final WatchingSessionPresenceWriter watchingSessionPresenceWriter;

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

        // watcher 소유권과 무관한 검증이라 임계 구역 밖에서 먼저 수행
        validateContentExists(contentId);

        WatchingSessionSnapshot snapshot;
        Optional<WatchingPresence> previousPresence;

        WatcherLock watcherLock = acquireWatcherLock(watcherId);
        // ★ 임계 구역 시작: 검증, DB 반영, 소유권 갱신을 묶어 원자적으로 처리
        try {
            synchronized (watcherLock) {
                Instant now = Instant.now();
                Instant expiresAt = now.plus(watchingSessionProperties.getSessionTtl());

                // DB 스냅샷 갱신
                WatchingSessionSnapshotWriter.UpsertResult upsertResult;
                upsertResult = retryOnceOnDuplicateKeyConflict(
                    () -> watchingSessionSnapshotWriter.upsert(watcherId, contentId, expiresAt));
                snapshot = upsertResult.snapshot();

                try {
                    // presence가 소유권의 원본이므로 실패를 삼키지 않고 그대로 전파
                    previousPresence = watchingSessionPresenceWriter.swap(
                        watcherId, snapshot.getId(), contentId, sessionId, subscriptionId, snapshot.getCreatedAt(), normalizeToMicros(snapshot.getUpdatedAt()), watchingSessionProperties.getPresenceTtl());
                } catch (RuntimeException presenceFailure) {
                    if (upsertResult.isNewIdentity()) {
                        // snapshot.getId()로 조건부 삭제. watcherId만으로 지우면 그 사이 다른
                        // 인스턴스가 만든 새 세대를 함께 지울 수 있다.
                        watchingSessionSnapshotWriter.deleteById(watcherId, snapshot.getId(), snapshot.getUpdatedAt());
                    }
                    throw presenceFailure;
                }
            }
        } finally {
            releaseWatcherLock(watcherId);
        }

        WatchingSessionDto previous = previousPresence.map(this::enrichPresence).orElse(null);

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

    // delete 성격의 메서드 (원래 구현으로 복원 - DB 조회 없이 boolean만 반환)
    public boolean end(UUID watcherId, String currentSessionId, String currentSubscriptionId) {
        WatcherLock watcherLock = acquireWatcherLock(watcherId);
        try {
            synchronized (watcherLock) {
                Optional<DeletedSnapshot> deleted = watchingSessionPresenceWriter.deleteIfOwner(
                    watcherId, currentSessionId, currentSubscriptionId);
                if (deleted.isEmpty()) {
                    return false;
                }

                int deletedRows = watchingSessionSnapshotWriter.deleteById(watcherId, deleted.get().snapshotId(), deleted.get().snapshotUpdatedAt());
                if (deletedRows == 0) {
                    log.warn("presence 소유권 확인 후 DB 스냅샷이 이미 교체됨, 퇴장 처리 생략: "
                        + "watcherId={}, snapshotId={}", watcherId, deleted.get().snapshotId());
                    return false;
                }
                return true;
            }
        } finally {
            releaseWatcherLock(watcherId);
        }
    }

    /**
     * WebSocket 연결 자체가 끊긴 경우(DISCONNECT) 전용 종료 메서드.
     * subscriptionId는 비교하지 않고 sessionId만으로 소유권을 판정한다.
     *
     * end()와 달리 DB 삭제 직전에 스냅샷을 먼저 조회해 DTO로 반환한다. 호출자가 별도로
     * get(watcherId)을 먼저 호출해 DTO를 떼어두면, 그 사이 같은 연결에서 재구독이 끼어들 경우
     * 삭제된 콘텐츠와 브로드캐스트되는 콘텐츠가 어긋날 수 있어 이 메서드가 조회와 삭제를
     * 같은 임계 구역 안에서 묶는다.
     */
    public Optional<WatchingSessionDto> endByConnection(UUID watcherId, String sessionId) {
        WatcherLock watcherLock = acquireWatcherLock(watcherId);
        try {
            synchronized (watcherLock) {
                Optional<DeletedSnapshot> deleted = watchingSessionPresenceWriter.deleteIfOwnerSession(
                    watcherId, sessionId);
                if (deleted.isEmpty()) {
                    return Optional.empty();
                }

                UUID snapshotId = deleted.get().snapshotId();
                WatchingSessionSnapshot snapshot = watchingSessionSnapshotRepository.findById(snapshotId)
                    .orElse(null);

                int deletedRows = watchingSessionSnapshotWriter.deleteById(watcherId, snapshotId,  deleted.get().snapshotUpdatedAt());
                if (deletedRows == 0 || snapshot == null) {
                    log.warn("presence 소유권 확인 후 DB 스냅샷이 이미 교체됨, 퇴장 처리 생략: "
                        + "watcherId={}, snapshotId={}", watcherId, snapshotId);
                    return Optional.empty();
                }
                return Optional.of(enrich(snapshot));
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

    // swap이 반환한 이전 presence를 dto로 변환. DB 재조회 없이 presence에 저장된 정보만 사용
    private WatchingSessionDto enrichPresence(WatchingPresence presence) {
        User watcher = userRepository.findById(presence.watcherId())
            .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
        Content content = contentRepository.findById(presence.contentId())
            .orElseThrow(() -> new BusinessException(ErrorCode.CONTENT_NOT_FOUND));
        return WatchingSessionDto.from(presence, watcher, content);
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

     // 시청 중 신호를 받아 presence TTL과 DB 스냅샷 만료 시각을 함께 연장
    // 소유권 확인만 임계구역 안에서 수행하고 DB UPDATE와 Redis expire는 밖에서 처리 (두 갱신이 모두 조건부라 락 밖으로 빼도 안전)
    public void heartbeat(UUID watcherId, UUID contentId, String sessionId, String subscriptionId) {

        // 소유권 확인과 presence TTL 연장이 Redis 스크립트 하나로 처리됨 -> watcherLock을 거치지 않는다
        boolean renewed = watchingSessionPresenceWriter.renewIfOwner(
            watcherId, sessionId, subscriptionId, watchingSessionProperties.getPresenceTtl());

        if (!renewed) {
            log.debug("연장할 활성 세션이 없어 heartbeat 종료: watcherId={}, contentId={}",
                watcherId, contentId);
            return;
        }

        Instant now = Instant.now();
        int renewedRows;
        try {
            renewedRows = watchingSessionSnapshotWriter.renewExpiresAt(
                watcherId, contentId, now.plus(watchingSessionProperties.getSessionTtl()));
        } catch (RuntimeException e) {
            log.error("세션 만료 시각 갱신 실패: watcherId={}, contentId={}", watcherId, contentId, e);
            return;
        }

        if (renewedRows == 0) {
            // Redis는 소유권을 확인했는데 DB 행이 없는 상태
            recoverMissingSnapshot(watcherId, contentId, sessionId, subscriptionId, now);
        }
    }

    private void recoverMissingSnapshot(UUID watcherId, UUID contentId, String sessionId, String subscriptionId, Instant now) {
        // INSERT를 포함하는 upsert()를 사용하므로 start()와 동일하게 watcherLock으로 직렬호
        WatcherLock watcherLock = acquireWatcherLock(watcherId);
        try {
            synchronized (watcherLock) {
                Optional<WatchingSessionSnapshot> recovered;
                try {
                    recovered = retryOnceOnDuplicateKeyConflict(() -> watchingSessionSnapshotWriter.insertIfAbsent(
                        watcherId, contentId, now.plus(watchingSessionProperties.getSessionTtl())));
                } catch (RuntimeException e) {
                    log.error("DB 행 소실 후 복구 실패: watcherId={}, contentId={}", watcherId, contentId, e);
                    return;
                }

                if (recovered.isEmpty()) {
                    // watcherId에 대해 이미 다른 행이 존재함
                    log.debug("복구 시도 중 다른 행이 이미 존재해 물러남(낡은 heartbeat로 추정): watcherId={}, contentId={}",
                        watcherId, contentId);
                    return;
                }

                WatchingSessionSnapshot snapshot = recovered.get();
                boolean synced = watchingSessionPresenceWriter.updateSnapshotIdIfOwner(
                    watcherId, sessionId, subscriptionId, snapshot.getId(), normalizeToMicros(snapshot.getUpdatedAt()));

                if (!synced) {
                    // 방금 삽입한 행이 확실하므로(insertIfAbsent는 기존 행을 건드리지 않음) 안전하게 보상 삭제할 수 있다.
                    watchingSessionSnapshotWriter.deleteById(watcherId, snapshot.getId(), normalizeToMicros(snapshot.getUpdatedAt()));
                    log.warn("DB 행 재생성 직후 소유권이 이전돼 고아 행을 보상 삭제함: watcherId={}", watcherId);
                    return;
                }

                log.warn("presence는 존재하나 DB 행이 없어 재생성함(복구): watcherId={}, contentId={}, newSnapshotId={}",
                    watcherId, contentId, snapshot.getId());
            }
        } finally {
            releaseWatcherLock(watcherId);
        }
    }

    /**
     * 중복키 충돌(DataIntegrityViolationException)만 1회 재시도
     * 중복키가 아닌 무결성 위반(FK 위반 등)은 재시도 없이 즉시 전파한다.
     * backoff 없이 즉시 재시도한다
     */
    private <T> T retryOnceOnDuplicateKeyConflict(Supplier<T> operation) {
        try {
            return operation.get();
        } catch (DataIntegrityViolationException e) {
            if (!DbConflictUtils.isDuplicateKeyViolation(e)) {
                throw e;
            }
        }
        return operation.get();
    }
}
