package com.mopl.content.external.batch;

import java.time.LocalDateTime;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.explore.JobExplorer;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 외부 콘텐츠 수집 Job을 주기적으로 실행한다.
 * JobParameters에 실행 시각을 넣어야 매번 새로운 JobInstance로 인식되어 재실행된다
 * (Spring Batch는 동일한 JobParameters로는 이미 완료된 Job을 다시 실행하지 않는다).
 */
@Slf4j
@Component
public class ExternalContentJobScheduler {

    private final JobLauncher jobLauncher;
    private final Job externalContentCollectionJob;
    private final JobExplorer jobExplorer;

    public ExternalContentJobScheduler(
            @Qualifier("externalContentJobLauncher") JobLauncher jobLauncher,
            Job externalContentCollectionJob,
            JobExplorer jobExplorer) {
        this.jobLauncher = jobLauncher;
        this.externalContentCollectionJob = externalContentCollectionJob;
        this.jobExplorer = jobExplorer;
    }

    @Scheduled(cron = "${external-content-batch.cron}", zone = "${external-content-batch.zone}")
    public void runExternalContentCollectionJob() throws Exception {
        var runningExecutions = jobExplorer.findRunningJobExecutions(externalContentCollectionJob.getName());
        if (!runningExecutions.isEmpty()) {
            log.warn("외부 콘텐츠 수집 Job이 이미 실행 중이라 이번 트리거를 건너뜁니다. runningExecutionIds={}",
                    runningExecutions.stream().map(JobExecution::getId).toList());
            return;
        }

        JobParameters jobParameters = new JobParametersBuilder()
                .addLocalDateTime("runDateTime", LocalDateTime.now())
                .toJobParameters();
        JobExecution execution = jobLauncher.run(externalContentCollectionJob, jobParameters);
        log.info("외부 콘텐츠 수집 Job 실행 시작: jobExecutionId={}, status={}", execution.getId(), execution.getStatus());
    }
}