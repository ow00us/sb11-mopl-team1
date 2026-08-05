package com.mopl.content.external.mapping;

import static org.assertj.core.api.Assertions.assertThat;

import com.mopl.content.entity.Content;
import com.mopl.content.entity.ContentSource;
import com.mopl.content.entity.ContentType;
import com.mopl.content.repository.ContentRepository;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
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
class ContentUpsertConcurrencyIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    @Autowired
    ContentUpsertService contentUpsertService;

    @Autowired
    ContentRepository contentRepository;

    @Test
    @DisplayName("같은 external id로 동시에 upsert해도 예외 없이 하나의 Content만 남는다")
    void upsert_concurrentRaceOnSameExternalId_resultsInSingleRow() throws Exception {
        ExternalContentDraft draft = new ExternalContentDraft(
                ContentType.MOVIE, ContentSource.TMDB, "race-1",
                "제목", "설명", null, Set.of("action"));

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);

        Future<Content> f1 = executor.submit(() -> {
            ready.countDown();
            go.await();
            return contentUpsertService.upsert(draft);
        });
        Future<Content> f2 = executor.submit(() -> {
            ready.countDown();
            go.await();
            return contentUpsertService.upsert(draft);
        });

        assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
        go.countDown();

        // 수정 전 코드였다면 패자 쪽에서 "current transaction is aborted" 예외가 던져져
        // get()이 ExecutionException으로 실패했을 것이다. 여기서는 둘 다 정상 완료되어야 한다.
        Content result1 = f1.get(10, TimeUnit.SECONDS);
        Content result2 = f2.get(10, TimeUnit.SECONDS);
        executor.shutdown();

        assertThat(result1.getId()).isNotNull();
        assertThat(result2.getId()).isNotNull();

        Content persisted = contentRepository.findBySourceAndExternalId(ContentSource.TMDB, "race-1")
                .orElseThrow();
        assertThat(persisted.getTitle()).isEqualTo("제목");

        long count = contentRepository.findAll().stream()
                .filter(c -> c.getSource() == ContentSource.TMDB && "race-1".equals(c.getExternalId()))
                .count();
        assertThat(count).isEqualTo(1L);
    }
}