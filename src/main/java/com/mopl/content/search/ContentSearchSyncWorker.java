package com.mopl.content.search;

import com.mopl.content.entity.Content;
import com.mopl.content.repository.ContentRepository;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.document.Document;
import org.springframework.data.elasticsearch.core.mapping.IndexCoordinates;
import org.springframework.data.elasticsearch.core.query.UpdateQuery;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

// ContentSearchSyncListener가 레인에 위임한 실제 동기화 작업을 수행한다.
// 레인 스레드에서 실행되므로, 원래 트랜잭션과 무관하게 여기서 새로 트랜잭션을 연다.
@Slf4j
@Component
@RequiredArgsConstructor
public class ContentSearchSyncWorker {

    private static final IndexCoordinates CONTENTS_INDEX = IndexCoordinates.of("contents");

    private final ContentRepository contentRepository;
    private final ContentSearchRepository contentSearchRepository;
    private final ContentDocumentMapper contentDocumentMapper;
    private final ElasticsearchOperations elasticsearchOperations;

    @Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW)
    public void sync(UUID contentId) {
        try {
            Content content = contentRepository.findById(contentId).orElse(null);
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
            log.warn("콘텐츠 검색 색인 동기화 실패. contentId={}", contentId, e);
        }
    }

    public void delete(UUID contentId) {
        try {
            contentSearchRepository.deleteById(contentId.toString());
        } catch (Exception e) {
            log.warn("콘텐츠 검색 색인 삭제 실패. contentId={}", contentId, e);
        }
    }
}
