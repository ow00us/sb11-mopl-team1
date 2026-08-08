package com.mopl.content.external.mapping;

import com.mopl.content.entity.Content;
import com.mopl.content.repository.ContentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 신규 Content 삽입을 별도의 물리 트랜잭션(REQUIRES_NEW)으로 격리한다.
 *
 * PostgreSQL은 트랜잭션 내에서 하나의 statement라도 실패하면 해당 트랜잭션 전체를
 * abort 상태로 만들어 이후 같은 트랜잭션의 모든 쿼리를 거부한다. 유니크 제약 위반
 * 가능성이 있는 삽입을 호출자의 트랜잭션과 분리해야, 실패 이후 호출자가 정상적으로
 * 재조회·복구를 이어갈 수 있다.
 *
 * REQUIRES_NEW는 프록시를 통해 호출되어야 적용되므로 별도 빈으로 분리했다
 * (ContentUpsertService 안에서 self-invocation하면 프록시가 우회되어 전파 옵션이 무시된다).
 */
@Component
@RequiredArgsConstructor
public class ContentInsertExecutor {

    private final ContentRepository contentRepository;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Content insert(ExternalContentDraft draft) {
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
    }
}