package com.mopl.content.external.mapping;

import com.mopl.content.entity.Content;
import com.mopl.content.repository.ContentRepository;
import java.sql.SQLException;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ContentUpsertService {

    private static final String PG_UNIQUE_VIOLATION_SQLSTATE = "23505";

    private final ContentRepository contentRepository;

    @Transactional
    public Content upsert(ExternalContentDraft draft) {
        return contentRepository.findBySourceAndExternalId(draft.source(), draft.externalId())
                .map(existing -> applyUpdate(existing, draft))
                .orElseGet(() -> createOrRecoverFromRace(draft));
    }

    private Content applyUpdate(Content existing, ExternalContentDraft draft) {
        existing.update(draft.title(), draft.description(), draft.tags());
        existing.updateThumbnail(draft.thumbnailUrl());
        return existing;
    }

    private Content createOrRecoverFromRace(ExternalContentDraft draft) {
        try {
            Content content = Content.builder()
                    .type(draft.type())
                    .source(draft.source())
                    .externalId(draft.externalId())
                    .title(draft.title())
                    .description(draft.description())
                    .thumbnailUrl(draft.thumbnailUrl())
                    .build();
            draft.tags().forEach(content::addTag);

            return contentRepository.saveAndFlush(content);
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
