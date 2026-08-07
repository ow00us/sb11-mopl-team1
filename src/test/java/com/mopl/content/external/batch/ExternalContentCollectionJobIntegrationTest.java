package com.mopl.content.external.batch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.mopl.content.entity.ContentSource;
import com.mopl.content.external.sportsdb.SportsDbApiClient;
import com.mopl.content.external.sportsdb.dto.SportsDbEventSummary;
import com.mopl.content.external.tmdb.TmdbApiClient;
import com.mopl.content.external.tmdb.dto.TmdbMovieSummary;
import com.mopl.content.external.tmdb.dto.TmdbPopularMoviesResponse;
import com.mopl.content.external.tmdb.dto.TmdbPopularTvResponse;
import com.mopl.content.external.tmdb.dto.TmdbTvSummary;
import com.mopl.content.repository.ContentRepository;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.test.JobLauncherTestUtils;
import org.springframework.batch.test.context.SpringBatchTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest
@SpringBatchTest
@ActiveProfiles("test")
@Testcontainers
class ExternalContentCollectionJobIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    @Autowired
    JobLauncherTestUtils jobLauncherTestUtils;

    @Autowired
    ContentRepository contentRepository;

    @MockitoBean
    TmdbApiClient tmdbApiClient;

    @MockitoBean
    SportsDbApiClient sportsDbApiClient;

    @Test
    void job_collectsTmdbAndSportsDbContent_andSavesToDatabase() throws Exception {
        TmdbMovieSummary movie = new TmdbMovieSummary(9001L, "Test Movie", "movie overview", "/m.jpg", List.of(28));
        when(tmdbApiClient.getPopularMovies(1)).thenReturn(new TmdbPopularMoviesResponse(1, List.of(movie), 1));

        TmdbTvSummary tv = new TmdbTvSummary(9002L, "Test TV", "tv overview", "/t.jpg", List.of(18));
        when(tmdbApiClient.getPopularTvShows(1)).thenReturn(new TmdbPopularTvResponse(1, List.of(tv), 1));

        SportsDbEventSummary event = new SportsDbEventSummary(
                "9003", "Test Match", "Test League", "Soccer", LocalDate.now().toString(), "https://thumb.jpg", "Test Filename");
        when(sportsDbApiClient.getEventsByDay(LocalDate.now(), 4569)).thenReturn(List.of(event));

        JobParameters jobParameters = new JobParametersBuilder()
                .addLocalDateTime("runDateTime", LocalDateTime.now())
                .toJobParameters();

        var jobExecution = jobLauncherTestUtils.launchJob(jobParameters);

        assertThat(jobExecution.getStatus()).isEqualTo(BatchStatus.COMPLETED);

        assertThat(contentRepository.findBySourceAndExternalId(ContentSource.TMDB, "9001")).isPresent();
        assertThat(contentRepository.findBySourceAndExternalId(ContentSource.TMDB, "9002")).isPresent();
        assertThat(contentRepository.findBySourceAndExternalId(ContentSource.SPORTS_DB, "9003")).isPresent();
    }

    @Test
    void job_rerunWithSameExternalIds_doesNotCreateDuplicates() throws Exception {
        TmdbMovieSummary movie = new TmdbMovieSummary(9001L, "Test Movie", "movie overview", "/m.jpg", List.of(28));
        when(tmdbApiClient.getPopularMovies(1)).thenReturn(new TmdbPopularMoviesResponse(1, List.of(movie), 1));
        when(tmdbApiClient.getPopularTvShows(1)).thenReturn(new TmdbPopularTvResponse(1, List.of(), 0));
        when(sportsDbApiClient.getEventsByDay(LocalDate.now(), 4569)).thenReturn(List.of());

        jobLauncherTestUtils.launchJob(new JobParametersBuilder()
                .addLocalDateTime("runDateTime", LocalDateTime.now())
                .toJobParameters());
        jobLauncherTestUtils.launchJob(new JobParametersBuilder()
                .addLocalDateTime("runDateTime", LocalDateTime.now().plusSeconds(1))
                .toJobParameters());

        long count = contentRepository.findAll().stream()
                .filter(c -> c.getSource() == ContentSource.TMDB && "9001".equals(c.getExternalId()))
                .count();
        assertThat(count).isEqualTo(1);
    }
}