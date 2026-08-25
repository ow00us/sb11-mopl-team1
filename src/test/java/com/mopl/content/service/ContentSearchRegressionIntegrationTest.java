package com.mopl.content.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.mopl.content.dto.ContentDto;
import com.mopl.content.entity.Content;
import com.mopl.content.entity.ContentType;
import com.mopl.content.repository.ContentRepository;
import com.mopl.content.search.ContentDocument;
import com.mopl.content.search.ContentDocumentMapper;
import com.mopl.content.search.ContentSearchRepository;
import com.mopl.global.common.CursorResponse;
import java.math.BigDecimal;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.IndexOperations;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.ImageFromDockerfile;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * {@code ContentServiceImpl.getList()}가 Postgres({@link ContentRepository}) 기반에서
 * Elasticsearch({@code ContentSearchExecutor}) 기반으로 바뀌면서 회귀가 없는지 같은 입력으로
 * 양쪽을 직접 돌려 비교한다.
 *
 * <p>완전히 같아야 하는 것(회귀면 실패): typeEqual/tagsIn 필터, 세 가지 sortBy × 양방향 정렬 순서,
 * 커서 페이지네이션 경계. 달라도 되는 것(의도된 개선): keywordLike 형태소 분석 매칭 — ES(nori)가
 * Postgres LIKE보다 넓게 매칭할 수 있으므로, LIKE가 찾은 건 ES도 다 찾는지(상위집합)만 확인한다.
 *
 * <p>테스트 데이터는 {@link ContentRepository#saveAndFlush}로 Postgres에 직접 심고, 대응하는
 * {@link ContentDocument}는 {@link ContentDocumentMapper#toNewDocument}로 만들어
 * {@link ContentSearchRepository#save}로 직접 색인한다 — 비동기 동기화 이벤트 경로(
 * {@code ContentSearchSyncListener})는 다른 테스트에서 이미 검증했으므로 여기서는 거치지 않는다.
 *
 * <p>watcherCount는 {@code ContentDocumentMapper.toNewDocument()}가 항상 0으로 채우고, 시청
 * 세션도 심지 않으므로 old(실시간 집계)/new(ES 필드) 양쪽 다 0으로 고정된다 — 값 자체가 아니라
 * 정렬·2차 정렬(reviewCount) 로직만 검증하면 되기 때문이다.
 */
@SpringBootTest
@ActiveProfiles("test")
// application-test.yml이 ES 자동 설정 3개를 꺼서 대신 TestContentSearchAutoConfiguration이 목 빈을
// 채워주는데, 이 테스트는 진짜 ES가 필요하다. 그 3개는 다시 켜고 TestContentSearchAutoConfiguration만
// 제외한다 — 두 쪽을 다 켜 두면 @EnableElasticsearchRepositories가 등록하는 진짜
// ContentSearchRepository 빈과 목 빈 정의가 이름이 같아 BeanDefinitionOverrideException이 난다
// (@ConditionalOnMissingBean이 평가되는 시점엔 아직 진짜 리포지토리 등록 전이라 막아주지 못한다).
@TestPropertySource(properties = "spring.autoconfigure.exclude=com.mopl.support.search.TestContentSearchAutoConfiguration")
@Testcontainers
@Transactional
class ContentSearchRegressionIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    // docker/elasticsearch/Dockerfile과 동일한 이미지(analysis-nori 플러그인 포함)를 빌드해 쓴다.
    // korean_nori 분석기가 없으면 ContentDocument 인덱스 생성 자체가 실패한다.
    private static final ImageFromDockerfile ES_IMAGE = new ImageFromDockerfile("mopl-test-elasticsearch-nori", false)
            .withDockerfile(Paths.get("docker/elasticsearch/Dockerfile"));

    @Container
    static GenericContainer<?> elasticsearch = new GenericContainer<>(DockerImageName.parse(ES_IMAGE.get()))
            .withExposedPorts(9200)
            .withEnv("discovery.type", "single-node")
            .withEnv("xpack.security.enabled", "false")
            .withEnv("ES_JAVA_OPTS", "-Xms512m -Xmx512m")
            .waitingFor(Wait.forHttp("/").forPort(9200).forStatusCode(200))
            .withStartupTimeout(Duration.ofMinutes(5));

    @DynamicPropertySource
    static void esProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.elasticsearch.uris",
                () -> "http://" + elasticsearch.getHost() + ":" + elasticsearch.getMappedPort(9200));
    }

    @Autowired
    ContentRepository contentRepository;

    @Autowired
    ContentService contentService;

    @Autowired
    ElasticsearchOperations elasticsearchOperations;

    @Autowired
    ContentSearchRepository contentSearchRepository;

    @Autowired
    ContentDocumentMapper contentDocumentMapper;

    @BeforeEach
    void resetSearchIndex() {
        IndexOperations indexOps = elasticsearchOperations.indexOps(ContentDocument.class);
        if (indexOps.exists()) {
            indexOps.delete();
        }
        indexOps.createWithMapping();
    }

    // ── typeEqual / tagsIn 필터 (완전 일치 필요) ────────────────────────────────

    @Test
    @DisplayName("typeEqual 필터는 old(Postgres)/new(ES)가 같은 콘텐츠 ID 집합을 반환한다")
    void filtersByTypeEqual_matchesLegacyResult() {
        Content movie1 = seed("영화1", ContentType.MOVIE, BigDecimal.ZERO, 0, List.of());
        Content movie2 = seed("영화2", ContentType.MOVIE, BigDecimal.ZERO, 0, List.of());
        seed("드라마1", ContentType.TV_SERIES, BigDecimal.ZERO, 0, List.of());

        TypeFilter typeFilter = TypeFilter.of(ContentType.MOVIE);

        List<Content> oldResult = oldQuery(
                typeFilter.forOldRepository(), null, List.of(""), 0, "createdAt", "DESCENDING", 10);
        CursorResponse<ContentDto> newResult = contentService.getList(
                typeFilter.forNewExecutor(), null, List.of(), null, null, 10, "createdAt", "DESCENDING");

        assertThat(dtoIds(newResult.data())).containsExactlyInAnyOrderElementsOf(ids(oldResult));
        assertThat(dtoIds(newResult.data())).containsExactlyInAnyOrder(movie1.getId(), movie2.getId());
    }

    @Test
    @DisplayName("tagsIn 다중 태그 필터(AND)는 old/new가 같은 콘텐츠 ID 집합을 반환한다")
    void filtersByMultipleTags_matchesLegacyResult_withAndSemantics() {
        Content both = seed("둘다", ContentType.MOVIE, BigDecimal.ZERO, 0, List.of("action", "sf"));
        seed("액션만", ContentType.MOVIE, BigDecimal.ZERO, 0, List.of("action"));
        seed("무관", ContentType.MOVIE, BigDecimal.ZERO, 0, List.of());

        List<Content> oldResult = oldQuery(
                null, null, List.of("action", "sf"), 2, "createdAt", "DESCENDING", 10);
        CursorResponse<ContentDto> newResult = contentService.getList(
                null, null, List.of("action", "sf"), null, null, 10, "createdAt", "DESCENDING");

        assertThat(dtoIds(newResult.data())).containsExactlyInAnyOrderElementsOf(ids(oldResult));
        assertThat(dtoIds(newResult.data())).containsExactly(both.getId());
    }

    // ── 정렬 순서 (완전 일치 필요) ────────────────────────────────────────────

    @ParameterizedTest(name = "{0} {1} 정렬 순서가 old/new에서 동일하다")
    @CsvSource({
            "createdAt, ASCENDING",
            "createdAt, DESCENDING",
            "averageRating, ASCENDING",
            "averageRating, DESCENDING",
            "watcherCount, ASCENDING",
            "watcherCount, DESCENDING"
    })
    void sortOrder_matchesLegacyResult(String sortBy, String sortDirection) {
        // averageRating·reviewCount는 서로 다르게, createdAt은 심는 순서대로 자연스럽게 벌어지도록 seed().
        // watcherCount는 양쪽 다 0으로 고정되므로, watcherCount 정렬은 사실상 tie-break 로직(ASC: id,
        // DESC: reviewCount → id)만 검증하게 된다.
        seed("A", ContentType.MOVIE, new BigDecimal("1.0"), 5, List.of());
        seed("B", ContentType.MOVIE, new BigDecimal("3.0"), 2, List.of());
        seed("C", ContentType.MOVIE, new BigDecimal("2.0"), 8, List.of());
        seed("D", ContentType.MOVIE, new BigDecimal("4.0"), 1, List.of());

        List<Content> oldResult = oldQuery(null, null, List.of(""), 0, sortBy, sortDirection, 10);
        CursorResponse<ContentDto> newResult = contentService.getList(
                null, null, List.of(), null, null, 10, sortBy, sortDirection);

        assertThat(dtoIds(newResult.data())).containsExactlyElementsOf(ids(oldResult));
    }

    // ── 커서 페이지네이션 경계 (완전 일치 필요) ──────────────────────────────────

    @Test
    @DisplayName("커서 있는/없는 페이지 조회 경계가 old/new에서 동일하다")
    void pagination_matchesLegacyPageBoundaries() {
        seed("P1", ContentType.MOVIE, BigDecimal.ZERO, 0, List.of());
        seed("P2", ContentType.MOVIE, BigDecimal.ZERO, 0, List.of());
        seed("P3", ContentType.MOVIE, BigDecimal.ZERO, 0, List.of());

        // 1페이지 — 커서 없음
        List<Content> oldPage1 = oldQuery(null, null, List.of(""), 0, "createdAt", "DESCENDING", 2);
        CursorResponse<ContentDto> newPage1 = contentService.getList(
                null, null, List.of(), null, null, 2, "createdAt", "DESCENDING");

        assertThat(dtoIds(newPage1.data())).containsExactlyElementsOf(ids(oldPage1));
        assertThat(newPage1.hasNext()).isTrue();

        // 2페이지 — 커서 있음. old는 1페이지 마지막 행의 실제 필드값을 그대로 이어서 조회하고
        // (커서 인코딩/디코딩은 CursorUtils에서 이미 검증됐으므로 여기선 생략),
        // new는 1페이지가 실제로 반환한 opaque 커서를 그대로 쓴다.
        Content lastOld = oldPage1.get(oldPage1.size() - 1);
        List<Content> oldPage2 = contentRepository.findByCreatedAtDesc(
                null, null, List.of(""), 0, lastOld.getCreatedAt(), lastOld.getId().toString(), 2);
        CursorResponse<ContentDto> newPage2 = contentService.getList(
                null, null, List.of(), newPage1.nextCursor(), newPage1.nextIdAfter(), 2, "createdAt", "DESCENDING");

        assertThat(dtoIds(newPage2.data())).containsExactlyElementsOf(ids(oldPage2));
        assertThat(newPage2.hasNext()).isFalse();
    }

    // ── keywordLike (상위집합만 확인 — ES가 더 찾는 건 회귀 아님) ───────────────

    @Test
    @DisplayName("keywordLike: ES(nori) 결과는 Postgres LIKE 결과의 상위집합이다")
    void keywordLike_esResultsSupersetOfLegacyLikeResults() {
        Content marathon1 = seed("마라톤을 달리는 이야기", ContentType.MOVIE, BigDecimal.ZERO, 0, List.of());
        Content marathon2 = seed("마라톤 완주 이야기", ContentType.MOVIE, BigDecimal.ZERO, 0, List.of());
        seed("축구 경기 결과", ContentType.SPORT, BigDecimal.ZERO, 0, List.of());

        List<Content> oldResult = oldQuery(null, "마라톤", List.of(""), 0, "createdAt", "DESCENDING", 10);
        CursorResponse<ContentDto> newResult = contentService.getList(
                null, "마라톤", List.of(), null, null, 10, "createdAt", "DESCENDING");

        // 전제 확인: LIKE가 실제로 뭔가는 찾아야 상위집합 검증이 의미가 있다.
        assertThat(oldResult).extracting(Content::getId)
                .containsExactlyInAnyOrder(marathon1.getId(), marathon2.getId());
        assertThat(dtoIds(newResult.data())).containsAll(ids(oldResult));
    }

    // ── 헬퍼 ─────────────────────────────────────────────────────────────────

    /**
     * old(ContentRepository)는 이미 변환된 enum name을, new(ContentSearchExecutor 경유
     * ContentService)는 raw camelCase API 값을 받아야 한다 — 헷갈리기 쉬운 지점이라 헬퍼로 묶는다.
     */
    private record TypeFilter(String forOldRepository, String forNewExecutor) {
        static TypeFilter of(ContentType type) {
            return new TypeFilter(type.name(), type.getApiValue());
        }
    }

    // Content 엔티티를 Postgres에 심고, 같은 내용의 ContentDocument를 비동기 이벤트 없이 직접 ES에도 색인한다.
    // 호출 사이 약간의 간격을 둬서, 같은 초 안에 여러 건을 심어도 createdAt이 자연스럽게 벌어지게 한다
    // (JPA Auditing이 심는 시점의 now()로 createdAt을 채우므로, 리플렉션으로 미리 넣어도 저장 시 덮어써진다).
    private Content seed(String title, ContentType type, BigDecimal averageRating, long reviewCount, List<String> tags) {
        Content content = Content.builder()
                .type(type)
                .title(title)
                .description(title + " 설명")
                .build();
        tags.forEach(content::addTag);
        ReflectionTestUtils.setField(content, "averageRating", averageRating);
        ReflectionTestUtils.setField(content, "reviewCount", reviewCount);
        Content saved = contentRepository.saveAndFlush(content);

        contentSearchRepository.save(contentDocumentMapper.toNewDocument(saved));

        try {
            Thread.sleep(10);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return saved;
    }

    // ContentServiceImpl이 ES로 전환되기 전 fetchPage()가 하던 것과 같은 old(Postgres) 쪽 디스패치.
    // 커서 없는 전체 조회 비교용이라 커서 파라미터는 항상 null로 고정한다.
    private List<Content> oldQuery(
            String typeStr, String keywordLike, List<String> tags, int tagCount,
            String sortBy, String sortDirection, int limit) {
        boolean isAsc = "ASCENDING".equalsIgnoreCase(sortDirection);
        Instant now = Instant.now();

        if ("watcherCount".equals(sortBy)) {
            return isAsc
                    ? contentRepository.findByWatcherCountAsc(typeStr, keywordLike, tags, tagCount, null, null, now, limit)
                    : contentRepository.findByWatcherCountDesc(
                            typeStr, keywordLike, tags, tagCount, null, null, null, now, limit);
        }
        if ("averageRating".equals(sortBy)) {
            return isAsc
                    ? contentRepository.findByAverageRatingAsc(typeStr, keywordLike, tags, tagCount, null, null, limit)
                    : contentRepository.findByAverageRatingDesc(typeStr, keywordLike, tags, tagCount, null, null, limit);
        }
        return isAsc
                ? contentRepository.findByCreatedAtAsc(typeStr, keywordLike, tags, tagCount, null, null, limit)
                : contentRepository.findByCreatedAtDesc(typeStr, keywordLike, tags, tagCount, null, null, limit);
    }

    private List<UUID> ids(List<Content> contents) {
        return contents.stream().map(Content::getId).toList();
    }

    private List<UUID> dtoIds(List<ContentDto> dtos) {
        return dtos.stream().map(ContentDto::id).toList();
    }
}
