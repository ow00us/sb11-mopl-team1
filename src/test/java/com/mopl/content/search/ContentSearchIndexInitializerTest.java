package com.mopl.content.search;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.mopl.content.entity.Content;
import com.mopl.content.entity.ContentType;
import com.mopl.content.repository.ContentRepository;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.IndexOperations;
import org.springframework.data.elasticsearch.core.query.Query;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * ContentSearchIndexInitializer 의 백필이 트랜잭션 밖에서 Content.tags(지연 로딩)를 건드려
 * LazyInitializationException 으로 조용히 실패하던 버그의 회귀 테스트.
 *
 * 실제 Hibernate 지연 로딩 프록시가 있어야 재현되는 예외라 목 기반 단위 테스트로는 잡히지
 * 않으므로 Testcontainers Postgres 로 검증한다. ES 는 test 프로파일이
 * {@link com.mopl.support.search.TestContentSearchAutoConfiguration} 으로 이미 Mockito 목을
 * 채워주므로 실제로 띄우지 않는다 — 그 목 빈을 그대로 주입받아 인덱스가 "이미 존재하고 문서 0건"
 * 인 것처럼 스텁해 백필 경로만 태운다. ({@code @MockitoSpyBean} 으로 감싸면 같은 워커 JVM 의
 * 다른 ContentSearchRepository 단위 테스트에 Mockito 상태가 새어 verifyNoInteractions 가
 * 깨지므로 쓰지 않는다.)
 *
 * run() 은 모든 예외를 catch 해서 로깅만 하므로 "예외를 던지지 않는다"만으로는 회귀를 잡지
 * 못한다. 실제 방어선은 두 번째 테스트의 {@code verify(...).saveAll(...)} 로, 버그가 있으면
 * 스트림 매핑 중 LazyInitializationException 이 나 saveAll 이 아예 호출되지 않아 실패한다.
 */
@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
class ContentSearchIndexInitializerTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    @Autowired
    ContentSearchIndexInitializer contentSearchIndexInitializer;

    @Autowired
    ContentRepository contentRepository;

    // test 프로파일이 채워주는 Mockito 목 빈들. 컨텍스트 기동 시 ApplicationRunner 가 한 번
    // 돌면서 남긴 상호작용/스텁을 지우고 시작하기 위해 매 테스트마다 reset 한다.
    @Autowired
    ContentSearchRepository contentSearchRepository;

    @Autowired
    ElasticsearchOperations elasticsearchOperations;

    @BeforeEach
    void resetSearchMocksAndStubIndex() {
        reset(contentSearchRepository, elasticsearchOperations);
        IndexOperations indexOps = mock(IndexOperations.class);
        when(indexOps.exists()).thenReturn(true);
        when(elasticsearchOperations.indexOps(ContentDocument.class)).thenReturn(indexOps);
        when(elasticsearchOperations.count(any(Query.class), eq(ContentDocument.class))).thenReturn(0L);
    }

    @Test
    @DisplayName("태그가 있는 콘텐츠를 백필해도 LazyInitializationException 없이 완료된다")
    void backfill_contentsWithTags_doesNotThrowLazyInitializationException() {
        Content withTags = Content.builder()
                .type(ContentType.MOVIE)
                .title("제목")
                .description("설명")
                .build();
        withTags.addTag("SF");
        withTags.addTag("Drama");
        contentRepository.saveAndFlush(withTags);

        assertThatCode(() -> contentSearchIndexInitializer.run(new DefaultApplicationArguments()))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("백필한 문서에 콘텐츠 ID, 정규화된 태그, 원본 표기 태그가 정확히 채워진다")
    void backfill_populatesContentIdAndNormalizedTags() {
        Content withTags = Content.builder()
                .type(ContentType.MOVIE)
                .title("제목")
                .description("설명")
                .build();
        withTags.addTag("SF");
        contentRepository.saveAndFlush(withTags);

        contentSearchIndexInitializer.run(new DefaultApplicationArguments());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ContentDocument>> captor = ArgumentCaptor.forClass(List.class);
        verify(contentSearchRepository, atLeastOnce()).saveAll(captor.capture());
        ContentDocument document = captor.getAllValues().stream()
                .flatMap(List::stream)
                .filter(doc -> doc.getContentId().equals(withTags.getId().toString()))
                .findFirst()
                .orElseThrow();
        // tags는 검색·필터용 정규화 값, displayTags는 화면 노출용 원본 표기다.
        // ContentDocumentMapper.toNewDocument()가 각각 content.getNormalizedTags()/getTags()로 채운다.
        assertThat(document.getTags()).containsExactly("sf");
        assertThat(document.getDisplayTags()).containsExactly("SF");
        assertThat(document.getId()).isEqualTo(withTags.getId().toString());
    }
}
