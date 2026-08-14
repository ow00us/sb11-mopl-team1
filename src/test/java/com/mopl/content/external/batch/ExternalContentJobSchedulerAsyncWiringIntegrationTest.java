package com.mopl.content.external.batch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.convention.TestBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
class ExternalContentJobSchedulerAsyncWiringIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    @Autowired
    ExternalContentJobScheduler scheduler;

    // JobRegistrySmartInitializingSingleton이 컨텍스트 리프레시 중(테스트 메서드가 실행되기 전)에
    // 모든 Job 빈의 getName()을 호출해 JobRegistry에 등록하므로, @MockitoBean으로 만든 목의
    // 이름을 테스트 메서드 안에서 뒤늦게 스텁하면 그 전에 컨텍스트 로딩 자체가
    // "Job configuration must have a name."으로 실패한다.
    // @TestBean의 정적 팩토리 메서드는 그 시점 이전에 실행되므로 이름을 미리 스텁해둔다.
    @TestBean
    Job externalContentCollectionJob;

    static Job externalContentCollectionJob() {
        Job job = mock(Job.class);
        when(job.getName()).thenReturn("externalContentCollectionJob");
        return job;
    }

    @Test
    @DisplayName("실제 컨텍스트에서 스케줄러는 비동기 JobLauncher로 주입돼 Job 완료를 기다리지 않고 반환한다")
    void scheduler_realDi_usesAsyncJobLauncher() throws Exception {
        when(externalContentCollectionJob.getJobParametersValidator()).thenReturn(parameters -> { });

        CountDownLatch jobStartedLatch = new CountDownLatch(1);
        CountDownLatch releaseJobLatch = new CountDownLatch(1);
        doAnswer(invocation -> {
            jobStartedLatch.countDown();
            releaseJobLatch.await(5, TimeUnit.SECONDS);
            return null;
        }).when(externalContentCollectionJob).execute(any(JobExecution.class));

        try {
            long start = System.nanoTime();
            scheduler.runExternalContentCollectionJob();
            long elapsedMs = Duration.ofNanos(System.nanoTime() - start).toMillis();

            assertThat(elapsedMs).isLessThan(500);
            assertThat(jobStartedLatch.await(2, TimeUnit.SECONDS)).isTrue();
        } finally {
            releaseJobLatch.countDown();
        }
    }
}