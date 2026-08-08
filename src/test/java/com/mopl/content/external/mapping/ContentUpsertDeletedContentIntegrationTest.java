package com.mopl.content.external.mapping;

import static org.assertj.core.api.Assertions.assertThat;

import com.mopl.content.entity.Content;
import com.mopl.content.entity.ContentSource;
import com.mopl.content.entity.ContentType;
import com.mopl.content.repository.ContentRepository;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
class ContentUpsertDeletedContentIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    @Autowired
    ContentUpsertService contentUpsertService;

    @Autowired
    ContentRepository contentRepository;

    @Test
    @DisplayName("저장 후 논리 삭제된 콘텐츠를 같은 (source, externalId)로 재수집하면 예외 없이 건너뛴다")
    void upsert_previouslyDeletedContent_skipsWithoutException() {
        // 저장
        Content saved = contentRepository.saveAndFlush(Content.builder()
                .type(ContentType.MOVIE)
                .source(ContentSource.TMDB)
                .externalId("deleted-race-1")
                .title("원래 제목")
                .description("원래 설명")
                .build());

        // 논리 삭제 (실제 어드민 삭제 흐름과 동일하게 repository.delete()로 @SQLDelete를 태운다)
        contentRepository.delete(saved);
        contentRepository.flush();

        // 동일 (source, externalId)로 재수집 시도
        ExternalContentDraft draft = new ExternalContentDraft(
                ContentType.MOVIE, ContentSource.TMDB, "deleted-race-1",
                "재수집된 제목", "재수집된 설명", null, Set.of("action"));

        Optional<Content> result = contentUpsertService.upsert(draft);

        // 예외 없이 건너뛴다 (수정 전 코드였다면 23505 위반 후 재조회도 실패해 예외가 전파됐을 것이다)
        assertThat(result).isEmpty();

        // 삭제된 행은 되살아나지 않고, 원래 내용도 덮어써지지 않는다
        Content stillDeleted = contentRepository.findBySourceAndExternalIdIncludingDeleted(
                        ContentSource.TMDB.name(), "deleted-race-1")
                .orElseThrow();
        assertThat(stillDeleted.getDeletedAt()).isNotNull();
        assertThat(stillDeleted.getTitle()).isEqualTo("원래 제목");
    }
}