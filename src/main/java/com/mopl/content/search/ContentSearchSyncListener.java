package com.mopl.content.search;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

// 콘텐츠 검색(ES) 색인 동기화 이벤트를 받아 contentId별 레인으로 위임한다.
// 같은 contentId의 sync/delete 작업이 뒤섞여 삭제된 문서가 되살아나는 걸 막기 위해,
// 실제 처리(ContentSearchSyncWorker)는 항상 같은 레인(단일 스레드)에서 제출 순서대로 실행된다.
@Component
@RequiredArgsConstructor
public class ContentSearchSyncListener {

    private final ContentSearchKeyedExecutor contentSearchKeyedExecutor;
    private final ContentSearchSyncWorker contentSearchSyncWorker;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handleSync(ContentSearchSyncEvent event) {
        contentSearchKeyedExecutor.execute(event.contentId(),
                () -> contentSearchSyncWorker.sync(event.contentId()));
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handleDelete(ContentSearchDeleteEvent event) {
        contentSearchKeyedExecutor.execute(event.contentId(),
                () -> contentSearchSyncWorker.delete(event.contentId()));
    }
}
