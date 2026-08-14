package com.mopl.content.external.batch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.mopl.content.external.mapping.ContentUpsertService;
import com.mopl.content.external.mapping.SportsDbContentMapper;
import com.mopl.content.external.mapping.TmdbContentMapper;
import com.mopl.content.external.sportsdb.SportsDbApiClient;
import com.mopl.content.external.sportsdb.SportsDbProperties;
import com.mopl.content.external.tmdb.TmdbApiClient;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * ExternalContentBatchConfig.jobLauncher()가 실제로 호출 스레드를 블로킹하지 않는지 검증한다.
 * 기본(동기) JobLauncher로 되돌리는 회귀를 잡기 위한 테스트이므로, mock JobLauncher가 아니라
 * 프로덕션 빈 생성 메서드를 그대로 호출해서 얻은 실제 JobLauncher로 검증한다.
 */
class ExternalContentBatchConfigJobLauncherTest {

    @Test
    @DisplayName("jobLauncher()가 반환한 빈은 Job 완료를 기다리지 않고 즉시 반환한다")
    void jobLauncher_returnsImmediately_withoutWaitingForJobCompletion() throws Exception {
        JobRepository jobRepository = mock(JobRepository.class);
        ExternalContentBatchConfig config = new ExternalContentBatchConfig(
                jobRepository,
                mock(PlatformTransactionManager.class),
                mock(TmdbApiClient.class),
                mock(SportsDbApiClient.class),
                mock(TmdbContentMapper.class),
                mock(SportsDbContentMapper.class),
                mock(ContentUpsertService.class),
                new ExternalContentBatchProperties(5, 3, 3, "Asia/Seoul"),
                new SportsDbProperties("https://example.com", "test-key", List.of(1)),
                mock(MeterRegistry.class));

        JobLauncher jobLauncher = config.jobLauncher();

        JobExecution jobExecution = new JobExecution(1L);
        when(jobRepository.createJobExecution(anyString(), any(JobParameters.class))).thenReturn(jobExecution);

        Job slowJob = mock(Job.class);
        when(slowJob.getName()).thenReturn("slowJob");
        when(slowJob.getJobParametersValidator()).thenReturn(parameters -> { });

        CountDownLatch jobStartedLatch = new CountDownLatch(1);
        CountDownLatch releaseJobLatch = new CountDownLatch(1);
        doAnswer(invocation -> {
            jobStartedLatch.countDown();
            releaseJobLatch.await(5, TimeUnit.SECONDS);
            return null;
        }).when(slowJob).execute(any(JobExecution.class));

        try {
            long start = System.nanoTime();
            jobLauncher.run(slowJob, new JobParameters());
            long elapsedMs = Duration.ofNanos(System.nanoTime() - start).toMillis();

            assertThat(elapsedMs).isLessThan(500);
            assertThat(jobStartedLatch.await(2, TimeUnit.SECONDS)).isTrue();
        } finally {
            releaseJobLatch.countDown();
        }
    }
}