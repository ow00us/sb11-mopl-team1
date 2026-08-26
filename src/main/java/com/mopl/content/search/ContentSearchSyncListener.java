package com.mopl.content.search;

import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

// 콘텐츠 검색(ES) 색인 동기화 이벤트를 받아 contentId별 레인으로 위임한다.
// 같은 contentId의 sync/delete 작업이 뒤섞여 삭제된 문서가 되살아나는 걸 막기 위해,
// 실제 처리(ContentSearchSyncWorker)는 항상 같은 레인(단일 스레드)에서 제출 순서대로 실행된다.
// 레인 큐가 가득 차 제출이 거부되면, 조용히 버리는 대신 재시도 대기열에 남긴다.
// sync/delete가 제출은 됐지만 실행 중 예외(ES 커넥션 오류 등)로 실패해 false를 반환한 경우도
// 마찬가지로 재시도 대기열에 남긴다 — 그렇지 않으면 DB와 ES가 어긋난 채로 조용히 남는다.
@Slf4j
@Component
@RequiredArgsConstructor
public class ContentSearchSyncListener {

    private final ContentSearchKeyedExecutor contentSearchKeyedExecutor;
    private final ContentSearchSyncWorker contentSearchSyncWorker;
    private final ContentSearchRetryService contentSearchRetryService;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handleSync(ContentSearchSyncEvent event) {
        try {
            contentSearchKeyedExecutor.execute(event.contentId(), () -> {
                if (!contentSearchSyncWorker.sync(event.contentId())) {
                    recordRejected(event.contentId(), ContentSearchRetryEventType.SYNC,
                            new IllegalStateException("콘텐츠 검색 동기화 실행 실패"));
                }
            });
        } catch (TaskRejectedException e) {
            recordRejected(event.contentId(), ContentSearchRetryEventType.SYNC, e);
        }
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handleDelete(ContentSearchDeleteEvent event) {
        try {
            contentSearchKeyedExecutor.execute(event.contentId(), () -> {
                if (!contentSearchSyncWorker.delete(event.contentId())) {
                    recordRejected(event.contentId(), ContentSearchRetryEventType.DELETE,
                            new IllegalStateException("콘텐츠 검색 삭제 동기화 실행 실패"));
                }
            });
        } catch (TaskRejectedException e) {
            recordRejected(event.contentId(), ContentSearchRetryEventType.DELETE, e);
        }
    }

    private void recordRejected(UUID contentId, ContentSearchRetryEventType eventType, Exception cause) {
        try {
            contentSearchRetryService.recordRejected(contentId, eventType);
            log.warn("콘텐츠 검색 동기화가 실패해 재시도 대기열에 기록했습니다. contentId={}, eventType={}",
                    contentId, eventType, cause);
        } catch (Exception recordFailure) {
            log.error("콘텐츠 검색 재시도 기록에도 실패했습니다. 이 이벤트는 유실됩니다. contentId={}, eventType={}",
                    contentId, eventType, recordFailure);
        }
    }
}
