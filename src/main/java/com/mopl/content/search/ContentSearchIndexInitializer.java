package com.mopl.content.search;

import com.mopl.content.entity.Content;
import com.mopl.content.repository.ContentRepository;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.IndexOperations;
import org.springframework.data.elasticsearch.core.query.Query;
import org.springframework.stereotype.Component;

/**
 * 애플리케이션 기동 시 contents 인덱스가 없으면 만들고, 문서가 없으면 Postgres에서 백필한다.
 * Spring Data Elasticsearch는 리포지토리가 있어도 인덱스를 자동으로 만들어주지 않아서, ES
 * 컨테이너가 떠 있어도 인덱스 자체가 없으면 조회가 index_not_found_exception으로 실패한다
 * (→ GlobalExceptionHandler의 catch-all이 500으로 응답한다). Postgres의 Flyway 역할을 ES
 * 쪽에서 대신한다.
 *
 * 인덱스를 만드는 것만으로는 충분하지 않다 — ES 색인은 콘텐츠 생성/수정 시 발행되는 이벤트로만
 * 채워지므로, 이 기능을 배포하는 시점에 이미 Postgres에 있던 기존 콘텐츠는 누군가 다시 수정하기
 * 전까진 검색 결과에 영원히 안 보인다. 그래서 인덱스 문서 개수가 0건이면 Postgres의 모든 콘텐츠를
 * 백필한다. "인덱스가 방금 새로 만들어졌는가"가 아니라 "지금 문서가 0건인가"로 판단하므로,
 * 최초 배포뿐 아니라 예전에 인덱스는 만들어졌는데 백필 도중 앱이 죽어 비어있는 상태로 남은
 * 경우에도 다음 재시작 때 자동으로 복구된다.
 *
 * 인덱스가 이미 있고 문서도 있으면 아무것도 하지 않는다 — 중복 저장/재백필하지 않는다.
 * 기존 인덱스의 매핑이 오래된 버전이어도 여기서 갱신하지 않는다 — 매핑 변경/리인덱싱은 이
 * 컴포넌트의 범위 밖이다.
 *
 * ES가 응답하지 않는 등 예외가 나도 애플리케이션 기동 자체는 실패시키지 않는다.
 * ES 조회 중 장애는 GlobalExceptionHandler의 catch-all이 이미 500으로 처리하므로,
 * 인덱스 초기화·백필 실패로 앱 자체가 못 뜨면 그 설계와 어긋난다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ContentSearchIndexInitializer implements ApplicationRunner {

    private static final int BACKFILL_PAGE_SIZE = 500;

    private final ElasticsearchOperations elasticsearchOperations;
    private final ContentSearchRepository contentSearchRepository;
    private final ContentRepository contentRepository;
    private final ContentDocumentMapper contentDocumentMapper;

    @Override
    public void run(ApplicationArguments args) {
        try {
            IndexOperations indexOps = elasticsearchOperations.indexOps(ContentDocument.class);
            if (!indexOps.exists()) {
                indexOps.createWithMapping();
                log.info("contents 인덱스를 생성했습니다.");
            } else {
                log.info("contents 인덱스가 이미 존재해 생성을 건너뜁니다.");
            }

            long documentCount = elasticsearchOperations.count(Query.findAll(), ContentDocument.class);
            if (documentCount > 0) {
                log.info("contents 인덱스에 문서가 이미 있어 백필을 건너뜁니다. count={}", documentCount);
                return;
            }
            backfillExistingContents();
        } catch (Exception e) {
            log.warn("contents 인덱스 초기화 또는 백필에 실패했습니다. ES 장애 시 조회는 500으로 처리됩니다.", e);
        }
    }

    // contents 인덱스가 비어 있을 때 Postgres의 기존 콘텐츠를 전부 색인한다.
    // Content는 @SQLRestriction("deleted_at IS NULL")이 걸려 있어 findAll()이 이미 소프트
    // 삭제된 콘텐츠를 제외하므로 별도 필터링이 필요 없다.
    //
    // 트랜잭션 밖에서 실행되므로 findAll(pageable) 결과를 그대로 매핑하면 지연 로딩 컬렉션인
    // Content.tags 접근 시 LazyInitializationException이 난다. 한 페이지의 ID만 뽑아
    // findAllWithTagsByIdIn()으로 태그까지 함께(@EntityGraph) 재조회한 뒤 매핑한다.
    // ID 목록 기준 메서드라 Pageable이 끼지 않아 "collection fetch + LIMIT" 인메모리
    // 페이지네이션 문제도 피한다. 페이지당 쿼리는 2번(ID 페이지 조회 + 태그 포함 재조회)이다.
    private void backfillExistingContents() {
        long backfilled = 0;
        Pageable pageable = PageRequest.of(0, BACKFILL_PAGE_SIZE);
        Slice<Content> slice;
        do {
            slice = contentRepository.findAll(pageable);
            List<UUID> ids = slice.getContent().stream().map(Content::getId).toList();
            List<ContentDocument> documents = ids.isEmpty()
                    ? List.of()
                    : contentRepository.findAllWithTagsByIdIn(ids).stream()
                            .map(contentDocumentMapper::toNewDocument)
                            .toList();
            if (!documents.isEmpty()) {
                contentSearchRepository.saveAll(documents);
                backfilled += documents.size();
            }
            pageable = pageable.next();
        } while (slice.hasNext());

        // toNewDocument()는 watcherCount를 항상 0으로 채우는데, 기존 설계대로
        // WatcherCountRefreshService의 다음 주기(기본 60초)에 실시간 값으로 자동 갱신된다.
        log.info("contents 인덱스 백필을 완료했습니다. count={}", backfilled);
    }
}
