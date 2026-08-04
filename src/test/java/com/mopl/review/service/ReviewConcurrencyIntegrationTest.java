package com.mopl.review.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.mopl.content.entity.Content;
import com.mopl.content.entity.ContentType;
import com.mopl.content.repository.ContentRepository;
import com.mopl.global.config.JpaConfig;
import com.mopl.review.dto.ReviewCreateRequest;
import com.mopl.user.entity.User;
import com.mopl.user.entity.UserRole;
import com.mopl.user.repository.UserRepository;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest
@ActiveProfiles("test")
@Import(JpaConfig.class)
@Testcontainers
class ReviewConcurrencyIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    @Autowired
    ReviewService reviewService;

    @Autowired
    ContentRepository contentRepository;

    @Autowired
    UserRepository userRepository;

    @Test
    @DisplayName("같은 콘텐츠에 리뷰가 동시에 여러 개 생성돼도 집계가 유실 없이 정확히 반영된다")
    void concurrentReviewCreation_doesNotLoseAggregateUpdates() throws InterruptedException {
        int threadCount = 10;
        Content content = contentRepository.save(Content.builder()
                .type(ContentType.MOVIE)
                .title("동시성 테스트 콘텐츠")
                .description("설명")
                .build());
        List<UUID> authorIds = IntStream.range(0, threadCount)
                .mapToObj(i -> userRepository.save(User.builder()
                        .email("concurrency-" + i + "@test.com")
                        .passwordHash("hash")
                        .name("tester" + i)
                        .role(UserRole.USER)
                        .locked(false)
                        .build()).getId())
                .toList();

        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch ready = new CountDownLatch(threadCount);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threadCount);
        AtomicInteger failures = new AtomicInteger();

        for (UUID authorId : authorIds) {
            executor.submit(() -> {
                try {
                    ready.countDown();
                    start.await();
                    reviewService.create(
                            new ReviewCreateRequest(content.getId(), "동시 리뷰", new BigDecimal("4.0")),
                            authorId);
                } catch (Exception e) {
                    failures.incrementAndGet();
                } finally {
                    done.countDown();
                }
            });
        }

        ready.await();
        start.countDown();
        boolean finished = done.await(30, TimeUnit.SECONDS);
        executor.shutdown();

        assertThat(finished).isTrue();
        assertThat(failures.get()).isZero();

        Content result = contentRepository.findById(content.getId()).orElseThrow();
        assertThat(result.getReviewCount()).isEqualTo(threadCount);
        assertThat(result.getAverageRating()).isEqualByComparingTo("4.0");
    }
}