package com.mopl.content.search;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.IndexOperations;
import org.springframework.stereotype.Component;

/**
 * 애플리케이션 기동 시 contents 인덱스가 없으면 만든다. Spring Data Elasticsearch는
 * 리포지토리가 있어도 인덱스를 자동으로 만들어주지 않아서, ES 컨테이너가 떠 있어도 인덱스
 * 자체가 없으면 조회가 index_not_found_exception으로 실패한다(→ GlobalExceptionHandler의
 * catch-all이 500으로 응답한다). Postgres의 Flyway 역할을 ES 쪽에서 대신한다.
 *
 * 인덱스가 이미 있으면 아무것도 하지 않는다. 기존 인덱스의 매핑이 오래된 버전이어도
 * 여기서 갱신하지 않는다 — 매핑 변경/리인덱싱은 이 컴포넌트의 범위 밖이다.
 *
 * ES가 응답하지 않는 등 예외가 나도 애플리케이션 기동 자체는 실패시키지 않는다.
 * ES 조회 중 장애는 GlobalExceptionHandler의 catch-all이 이미 500으로 처리하므로,
 * 인덱스 초기화 실패로 앱 자체가 못 뜨면 그 설계와 어긋난다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ContentSearchIndexInitializer implements ApplicationRunner {

    private final ElasticsearchOperations elasticsearchOperations;

    @Override
    public void run(ApplicationArguments args) {
        try {
            IndexOperations indexOps = elasticsearchOperations.indexOps(ContentDocument.class);
            if (indexOps.exists()) {
                log.info("contents 인덱스가 이미 존재해 초기화를 건너뜁니다.");
                return;
            }
            indexOps.createWithMapping();
            log.info("contents 인덱스를 생성했습니다.");
        } catch (Exception e) {
            log.warn("contents 인덱스 초기화에 실패했습니다. ES 장애 시 조회는 500으로 처리됩니다.", e);
        }
    }
}
