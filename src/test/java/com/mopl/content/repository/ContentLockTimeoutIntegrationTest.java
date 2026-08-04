package com.mopl.content.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.mopl.content.entity.Content;
import com.mopl.content.entity.ContentType;
import com.mopl.global.config.JpaConfig;
import java.util.UUID;
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
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest
@ActiveProfiles("test")
@Import(JpaConfig.class)
@Testcontainers
class ContentLockTimeoutIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    @Autowired
    ContentRepository contentRepository;

    @Autowired
    PlatformTransactionManager transactionManager;

    @Test
    @DisplayName("다른 트랜잭션이 콘텐츠 행 락을 오래 점유하면 lock_timeout 이후 예외로 실패한다")
    void findByIdForUpdate_failsAfterLockTimeout_whenRowLockedTooLong() throws Exception {
        Content content = contentRepository.save(Content.builder()
                .type(ContentType.MOVIE)
                .title("락 타임아웃 테스트")
                .description("설명")
                .build());
        UUID contentId = content.getId();

        CountDownLatch lockAcquired = new CountDownLatch(1);
        CountDownLatch releaseLock = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        TransactionTemplate transactionTemplate = new TransactionTemplate(transactionManager);

        // 락을 쥐고 오래 놓지 않는 트랜잭션(holder)
        Future<?> holder = executor.submit(() -> transactionTemplate.execute(status -> {
            contentRepository.findByIdForUpdate(contentId);
            lockAcquired.countDown();
            try {
                releaseLock.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return null;
        }));

        assertThat(lockAcquired.await(5, TimeUnit.SECONDS)).isTrue();

        // 같은 행의 락을 기다리는 트랜잭션(waiter) - lock_timeout 이내에 실패해야 한다
        long start = System.currentTimeMillis();
        Future<Exception> waiter = executor.submit(() -> {
            try {
                transactionTemplate.execute(status -> {
                    contentRepository.setLockTimeoutForReviewAggregateLock();
                    contentRepository.findByIdForUpdate(contentId);
                    return null;
                });
                return null;
            } catch (Exception e) {
                return e;
            }
        });

        Exception failure = waiter.get(10, TimeUnit.SECONDS);
        long elapsedMs = System.currentTimeMillis() - start;

        releaseLock.countDown();
        holder.get(10, TimeUnit.SECONDS);
        executor.shutdown();

        assertThat(failure).isNotNull();
        assertThat(elapsedMs).isLessThan(8000L); // lock_timeout(5s)보다 넉넉한 상한
    }
}