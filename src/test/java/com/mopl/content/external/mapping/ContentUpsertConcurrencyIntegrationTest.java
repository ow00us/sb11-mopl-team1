package com.mopl.content.external.mapping;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.mopl.content.entity.Content;
import com.mopl.content.entity.ContentSource;
import com.mopl.content.entity.ContentType;
import com.mopl.content.repository.ContentRepository;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CyclicBarrier;
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
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
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

    @MockitoSpyBean
    ContentInsertExecutor contentInsertExecutor;

    @MockitoSpyBean
    ContentRepository contentRepository;

    @Test
    @DisplayName("동시에 upsert하면 한쪽은 유니크 위반 후 재조회로 복구되어 하나의 Content만 남는다")
    void upsert_concurrentRaceOnSameExternalId_recoversViaDuplicateKeyPath() throws Exception {
        ExternalContentDraft draft = new ExternalContentDraft(
                ContentType.MOVIE, ContentSource.TMDB, "race-1",
                "제목", "설명", null, Set.of("action"));

        // 두 스레드 모두 "기존 콘텐츠 없음" 조회를 마친 뒤, 실제 INSERT 직전에
        // 서로를 기다리게 해서 unique 제약 경합을 매번 결정적으로 재현한다.
        CyclicBarrier insertBarrier = new CyclicBarrier(2);
        doAnswer(invocation -> {
            insertBarrier.await(10, TimeUnit.SECONDS);
            return invocation.callRealMethod();
        }).when(contentInsertExecutor).insert(any(ExternalContentDraft.class));

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Optional<Content>> f1 = executor.submit(() -> contentUpsertService.upsert(draft));
            Future<Optional<Content>> f2 = executor.submit(() -> contentUpsertService.upsert(draft));

            // 수정 전 코드였다면 패자 쪽에서 "current transaction is aborted" 예외가 던져져
            // get()이 ExecutionException으로 실패했을 것이다. 여기서는 둘 다 정상 완료되어야 한다.
            Content result1 = f1.get(10, TimeUnit.SECONDS).orElseThrow();
            Content result2 = f2.get(10, TimeUnit.SECONDS).orElseThrow();

            assertThat(result1.getId()).isNotNull();
            assertThat(result2.getId()).isNotNull();

            List<Content> matching = contentRepository.findAll().stream()
                    .filter(c -> c.getSource() == ContentSource.TMDB && "race-1".equals(c.getExternalId()))
                    .toList();
            assertThat(matching).hasSize(1);
            assertThat(matching.get(0).getTitle()).isEqualTo("제목");

            // 두 스레드 모두 실제 INSERT를 시도했음(동시 경합 강제)과, 그중 패자가
            // 유니크 위반 이후 재조회를 한 번 더 거쳐 복구했음을 정확히 증명한다.
            // 2(각 스레드의 최초 조회) + 1(패자의 복구 재조회) = 3.
            verify(contentInsertExecutor, times(2)).insert(any(ExternalContentDraft.class));
            verify(contentRepository, times(3))
                    .findBySourceAndExternalId(eq(ContentSource.TMDB), eq("race-1"));
        } finally {
            executor.shutdownNow();
            executor.awaitTermination(5, TimeUnit.SECONDS);
        }
    }
}