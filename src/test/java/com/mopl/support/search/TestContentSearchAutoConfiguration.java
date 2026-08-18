package com.mopl.support.search;

import com.mopl.content.search.ContentSearchRepository;
import org.mockito.Mockito;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/**
 * test 프로파일은 application-test.yml 에서 Elasticsearch 자동 설정을 꺼서
 * {@code ContentSearchRepository} 실빈이 만들어지지 않는다. 그런데 이 리포지토리는
 * 검색 기능과 무관한 도메인 {@code @SpringBootTest} 에서도 항상 로드되는
 * ContentSearchSyncListener, WatcherCountRefreshService 가 주입받으므로,
 * 실제 ES 연결 없이 컨텍스트를 띄울 수 있도록 목 빈을 대신 채워준다.
 */
@AutoConfiguration
public class TestContentSearchAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(ContentSearchRepository.class)
    public ContentSearchRepository contentSearchRepository() {
        return Mockito.mock(ContentSearchRepository.class);
    }
}
