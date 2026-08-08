package com.mopl.content.external.batch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.launch.JobLauncher;

class ExternalContentJobSchedulerTest {

    @Test
    @DisplayName("스케줄 실행 시 runDateTime 파라미터를 포함해 Job을 실행한다")
    void runExternalContentCollectionJob_launchesJobWithRunDateTimeParameter() throws Exception {
        JobLauncher jobLauncher = mock(JobLauncher.class);
        Job job = mock(Job.class);
        JobExecution jobExecution = mock(JobExecution.class);
        when(jobLauncher.run(eq(job), any(JobParameters.class))).thenReturn(jobExecution);

        ExternalContentJobScheduler scheduler = new ExternalContentJobScheduler(jobLauncher, job);
        scheduler.runExternalContentCollectionJob();

        ArgumentCaptor<JobParameters> jobParametersCaptor = ArgumentCaptor.forClass(JobParameters.class);
        verify(jobLauncher).run(eq(job), jobParametersCaptor.capture());
        assertThat(jobParametersCaptor.getValue().getLocalDateTime("runDateTime")).isNotNull();
    }
}