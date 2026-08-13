package com.mopl.content.external.batch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.explore.JobExplorer;
import org.springframework.batch.core.launch.JobLauncher;

class ExternalContentJobSchedulerTest {

    @Test
    @DisplayName("스케줄 실행 시 runDateTime 파라미터를 포함해 Job을 실행한다")
    void runExternalContentCollectionJob_launchesJobWithRunDateTimeParameter() throws Exception {
        JobLauncher jobLauncher = mock(JobLauncher.class);
        Job job = mock(Job.class);
        JobExplorer jobExplorer = mock(JobExplorer.class);
        JobExecution jobExecution = mock(JobExecution.class);
        when(job.getName()).thenReturn("externalContentCollectionJob");
        when(jobExplorer.findRunningJobExecutions(anyString())).thenReturn(Set.of());
        when(jobLauncher.run(eq(job), any(JobParameters.class))).thenReturn(jobExecution);

        ExternalContentJobScheduler scheduler = new ExternalContentJobScheduler(jobLauncher, job, jobExplorer);
        scheduler.runExternalContentCollectionJob();

        ArgumentCaptor<JobParameters> jobParametersCaptor = ArgumentCaptor.forClass(JobParameters.class);
        verify(jobLauncher).run(eq(job), jobParametersCaptor.capture());
        assertThat(jobParametersCaptor.getValue().getLocalDateTime("runDateTime")).isNotNull();
    }

    @Test
    @DisplayName("이미 실행 중인 Job이 있으면 새로 시작하지 않고 건너뛴다")
    void runExternalContentCollectionJob_skipsWhenJobAlreadyRunning() throws Exception {
        JobLauncher jobLauncher = mock(JobLauncher.class);
        Job job = mock(Job.class);
        JobExplorer jobExplorer = mock(JobExplorer.class);
        JobExecution runningExecution = mock(JobExecution.class);
        when(job.getName()).thenReturn("externalContentCollectionJob");
        when(jobExplorer.findRunningJobExecutions(anyString())).thenReturn(Set.of(runningExecution));

        ExternalContentJobScheduler scheduler = new ExternalContentJobScheduler(jobLauncher, job, jobExplorer);
        scheduler.runExternalContentCollectionJob();

        verify(jobLauncher, never()).run(any(), any());
    }
}