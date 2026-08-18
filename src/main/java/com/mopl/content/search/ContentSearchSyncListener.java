package com.mopl.content.search;

import com.mopl.content.entity.Content;
import com.mopl.content.repository.ContentRepository;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.document.Document;
import org.springframework.data.elasticsearch.core.mapping.IndexCoordinates;
import org.springframework.data.elasticsearch.core.query.UpdateQuery;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

// 콘텐츠 검색(ES) 색인 동기화 Listener. ES 장애가 콘텐츠 CUD 트랜잭션에 영향을 주면 안 되므로
// 커밋 이후 별도 스레드에서 동작하고, 실패해도 예외를 삼키고 로그만 남긴다.
@Slf4j
@Component
@RequiredArgsConstructor
public class ContentSearchSyncListener {

    private static final IndexCoordinates CONTENTS_INDEX = IndexCoordinates.of("contents");

    private final ContentRepository contentRepository;
    private final ContentSearchRepository contentSearchRepository;
    private final ContentDocumentMapper contentDocumentMapper;
    private final ElasticsearchOperations elasticsearchOperations;

    @Async("contentSearchSyncExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW)
    public void handleSync(ContentSearchSyncEvent event) {
        try {
            Content content = contentRepository.findById(event.contentId()).orElse(null);
            if (content == null) {
                return;
            }

            String id = content.getId().toString();
            if (!contentSearchRepository.existsById(id)) {
                contentSearchRepository.save(contentDocumentMapper.toNewDocument(content));
                return;
            }

            Map<String, Object> updateFields = contentDocumentMapper.toUpdateFields(content);
            UpdateQuery updateQuery = UpdateQuery.builder(id)
                    .withDocument(Document.from(updateFields))
                    .build();
            elasticsearchOperations.update(updateQuery, CONTENTS_INDEX);
        } catch (Exception e) {
            log.warn("콘텐츠 검색 색인 동기화 실패. contentId={}", event.contentId(), e);
        }
    }

    @Async("contentSearchSyncExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handleDelete(ContentSearchDeleteEvent event) {
        try {
            contentSearchRepository.deleteById(event.contentId().toString());
        } catch (Exception e) {
            log.warn("콘텐츠 검색 색인 삭제 실패. contentId={}", event.contentId(), e);
        }
    }
}
