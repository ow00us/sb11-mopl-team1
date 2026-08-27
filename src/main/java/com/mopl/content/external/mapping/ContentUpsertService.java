package com.mopl.content.external.mapping;

import com.mopl.content.entity.Content;
import com.mopl.content.repository.ContentRepository;
import com.mopl.content.search.ContentSearchSyncEvent;
import java.sql.SQLException;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ContentUpsertService {

    private static final String PG_UNIQUE_VIOLATION_SQLSTATE = "23505";

    private final ContentRepository contentRepository;
    private final ContentInsertExecutor contentInsertExecutor;
    private final ApplicationEventPublisher eventPublisher;

    @Transactional
    public Optional<Content> upsert(ExternalContentDraft draft) {
        if (isAlreadyDeleted(draft)) {
            // 관리자가 명시적으로 삭제한 콘텐츠는 외부 동기화가 되살리지 않는다.
            return Optional.empty();
        }
        Content result = contentRepository.findBySourceAndExternalId(draft.source(), draft.externalId())
                .map(existing -> applyUpdate(existing, draft))
                .orElseGet(() -> createOrRecoverFromRace(draft));
        // ContentServiceImpl과 동일하게 저장/갱신 직후 검색 색인 동기화 이벤트를 발행한다.
        // ContentSearchSyncListener가 @TransactionalEventListener(AFTER_COMMIT)라 이 트랜잭션이
        // 커밋된 뒤에만 실제 동기화가 실행되고, 롤백되면 이벤트가 소비되지 않는다.
        eventPublisher.publishEvent(new ContentSearchSyncEvent(result.getId()));
        return Optional.of(result);
    }

    private boolean isAlreadyDeleted(ExternalContentDraft draft) {
        if (draft.source() == null || draft.externalId() == null) {
            return false;
        }
        return contentRepository
                .findBySourceAndExternalIdIncludingDeleted(draft.source().name(), draft.externalId())
                .map(content -> content.getDeletedAt() != null)
                .orElse(false);
    }

    private Content applyUpdate(Content existing, ExternalContentDraft draft) {
        existing.update(draft.title(), draft.description(), draft.tags());
        existing.updateThumbnail(draft.thumbnailUrl());
        return existing;
    }

    private Content createOrRecoverFromRace(ExternalContentDraft draft) {
        try {
            // 별도 트랜잭션(REQUIRES_NEW)에서 삽입을 시도한다.
            // 유니크 제약 위반이 발생해도 그 트랜잭션만 롤백되고, 호출자의 트랜잭션은
            // 영향받지 않으므로 아래 재조회를 안전하게 수행할 수 있다.
            return contentInsertExecutor.insert(draft);
        } catch (DataIntegrityViolationException e) {
            if (!isDuplicateKeyViolation(e)) {
                throw e;
            }
            Content existing = contentRepository.findBySourceAndExternalId(draft.source(), draft.externalId())
                    .orElseThrow(() -> e);
            return applyUpdate(existing, draft);
        }
    }

    private boolean isDuplicateKeyViolation(DataIntegrityViolationException e) {
        if (e instanceof DuplicateKeyException) {
            return true;
        }
        Throwable cause = e.getMostSpecificCause();
        return cause instanceof SQLException sqlException
                && PG_UNIQUE_VIOLATION_SQLSTATE.equals(sqlException.getSQLState());
    }
}