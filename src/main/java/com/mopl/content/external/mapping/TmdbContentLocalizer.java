package com.mopl.content.external.mapping;

import com.mopl.content.entity.Content;
import com.mopl.content.repository.ContentRepository;
import com.mopl.content.search.ContentSearchSyncEvent;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 콘텐츠 한 건의 제목·설명만 갱신하고 검색 색인 동기화 이벤트를 발행하는, 콘텐츠 단위 트랜잭션 경계.
 *
 * <p>TMDB 현지화 백필은 콘텐츠 건수만큼 순차 처리되는데, 한 건이 실패해도 나머지가 이어져야 하므로
 * 갱신 단위를 콘텐츠 하나로 격리한다. {@code @Transactional}은 프록시를 통해 호출되어야 적용되므로
 * 반복문을 도는 {@link TmdbContentLocalizationBackfillService}와 분리된 별도 빈으로 둔다
 * (self-invocation하면 트랜잭션이 걸리지 않는다).
 *
 * <p>이벤트는 이 트랜잭션 안에서 발행한다. {@code ContentSearchSyncListener}가
 * {@code @TransactionalEventListener(AFTER_COMMIT)}라, 커밋된 뒤에만 실제 색인 동기화가 실행되고
 * 롤백되면 이벤트 자체가 소비되지 않는다({@code ContentUpsertService}와 같은 방식).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TmdbContentLocalizer {

    private final ContentRepository contentRepository;
    private final ApplicationEventPublisher eventPublisher;

    @Transactional
    public void localize(UUID contentId, String title, String overview) {
        Content content = contentRepository.findById(contentId).orElse(null);
        if (content == null) {
            // 백필 도중 삭제된 콘텐츠. 다음 콘텐츠로 넘어가면 된다.
            log.debug("TMDB 현지화 대상 콘텐츠가 이미 없습니다. contentId={}", contentId);
            return;
        }
        // 태그는 그대로 유지해야 하므로 현재 태그를 그대로 넘긴다(제목·설명만 갱신).
        content.update(title, overview, content.getTags());
        eventPublisher.publishEvent(new ContentSearchSyncEvent(content.getId()));
    }
}
